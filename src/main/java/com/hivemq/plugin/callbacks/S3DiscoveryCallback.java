package com.hivemq.plugin.callbacks;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.util.StringInputStream;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.hivemq.plugin.configuration.Configuration;
import com.hivemq.spi.callback.cluster.ClusterDiscoveryCallback;
import com.hivemq.spi.callback.cluster.ClusterNodeAddress;
import com.hivemq.spi.services.PluginExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Christoph Schäbel
 */
public class S3DiscoveryCallback implements ClusterDiscoveryCallback {

    private static final Logger log = LoggerFactory.getLogger(S3DiscoveryCallback.class);
    private static final String SEPARATOR = "||||";
    private static final String SEPARATOR_REGEX = "\\|\\|\\|\\|";
    private static final String VERSION = "1";

    private final AmazonS3 s3;
    private final Configuration configuration;
    private final String bucketName;
    private final PluginExecutorService pluginExecutorService;

    private String objectKey;
    private String clusterId;
    private ClusterNodeAddress ownAddress;

    @Inject
    public S3DiscoveryCallback(final AmazonS3 s3,
                               final Configuration configuration,
                               final PluginExecutorService pluginExecutorService) {
        this.s3 = s3;
        this.configuration = configuration;
        this.pluginExecutorService = pluginExecutorService;
        this.bucketName = configuration.getBucketName();
    }

    @Override
    public void init(final String clusterId, final ClusterNodeAddress ownAddress) {

        this.clusterId = clusterId;
        this.ownAddress = ownAddress;
        objectKey = configuration.getFilePrefix() + clusterId;

        saveOwnInformationToS3();


        final long updateInterval = configuration.getOwnInformationUpdateInterval();
        if (updateInterval > 0) {
            //schedule Task to update
            pluginExecutorService.scheduleAtFixedRate(new Runnable() {//TODO warum fixed rate? fixedDelay besser?
                @Override
                public void run() {
                    saveOwnInformationToS3();
                }
            }, updateInterval, updateInterval, TimeUnit.MINUTES);
        }
    }

    @Override
    public ListenableFuture<List<ClusterNodeAddress>> getNodeAddresses() {

        final List<ClusterNodeAddress> addresses = new ArrayList<>();

        try {
            final ObjectListing objectListing = s3.listObjects(bucketName, configuration.getFilePrefix());
            readAllFiles(addresses, objectListing);

        } catch (AmazonClientException e) { //catches AmazonServiceException as well
            log.error("Error while trying to fetch data from Amazon S3.");
            //TODO was sollen wir machen wennn keine objectListings zurück kommen?
            //TODO in readAllFiles wird dann ja nichts ausgeführt und es gibt keine addresses?
        } catch (Exception e) {
            log.error("Error while trying to fetch data from Amazon S3.", e);

        }
        return Futures.immediateFuture(addresses);
    }

    private void saveOwnInformationToS3() {
        try {

            final String content = createFileContent(clusterId, ownAddress);
            final StringInputStream input;
            input = new StringInputStream(content);


            final ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(input.available());

            s3.putObject(bucketName, objectKey, input, metadata);
            input.close();
            log.debug("S3 node information updated");

        } catch (Exception e) {
            log.error("Not able to save node information to S3");
            log.debug("Original exception", e);
        }
    }

    private String createFileContent(final String clusterId, final ClusterNodeAddress ownAddress) {
        final String content = VERSION + SEPARATOR
                + Long.toString(System.currentTimeMillis()) + SEPARATOR
                + clusterId + SEPARATOR
                + ownAddress.getHost() + SEPARATOR
                + ownAddress.getPort() + SEPARATOR;

        return BaseEncoding.base64().encode(content.getBytes(StandardCharsets.UTF_8));
    }


    private void readAllFiles(final List<ClusterNodeAddress> addresses, final ObjectListing objectListing) {
        for (S3ObjectSummary objectSummary : objectListing.getObjectSummaries()) {
            try {
                final String key = objectSummary.getKey();
                final S3Object object;
                try {
                    object = s3.getObject(bucketName, key);
                } catch (AmazonServiceException e) {
                    log.debug("Not able to read file {} from S3: {}", key, e.getMessage());
                    continue;
                }

                try (final S3ObjectInputStream objectContent = object.getObjectContent();
                     final BufferedReader in = new BufferedReader(new InputStreamReader(objectContent))) {

                    final String fileContent = in.readLine();
                    final ClusterNodeAddress address = parseFileContent(fileContent, key);
                    if (address != null) {
                        addresses.add(address);
                    }

                    //the S3ObjectInputStream is handled with ARM as it implements AutoClosable
                } catch (IOException e) {
                    log.error("Unable to access AWS or parse files");
                }

                if (objectListing.isTruncated()) {
                    final ObjectListing objectListingNext = s3.listNextBatchOfObjects(objectListing);
                    //recursive call, if the listing was truncated and there are more objectListings to handle
                    readAllFiles(addresses, objectListingNext);

                }

            } catch (Exception e) {
                //clean up, if the exception wasn not caught inside
                log.error("Unable to access AWS and parse files due to unknown issue: ", e);
            }
        }
    }

    private ClusterNodeAddress parseFileContent(final String fileContent, final String key) {

        if (fileContent == null) {
            return null;
        }

        final String content;
        try {
            final byte[] decode = BaseEncoding.base64().decode(fileContent);
            if (decode == null) {
                log.debug("Not able to parse contents from S3-object '{}'", key);
                return null;
            }
            content = new String(decode, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.debug("Not able to parse contents from S3-object '{}'", key);
            return null;
        }

        final String[] split = content.split(SEPARATOR_REGEX);
        if (split.length < 4) {
            log.debug("Not able to parse contents from S3-object '{}'", key);
            return null;
        }

        final long expirationMinutes = configuration.getExpirationMinutes();


        //testing if file is expired and deleting it of this is the case
        if (expirationMinutes > 0) {
            final long expirationFromFile = Long.parseLong(split[1]);
            if (expirationFromFile + (expirationMinutes * 60000) < System.currentTimeMillis()) {
                log.debug("S3 object {} expired, deleting it.", key);
                s3.deleteObject(bucketName, key); //TODO wirklich deleten? es könnte ein Fehler in der config eines Nodes sein bzw andere Nodes könnten längere expiration times in der config haben oder?
                return null;
            }
        }

        final String host = split[3];
        if (host.length() < 1) {
            log.debug("Not able to parse contents from S3-object '{}'", key);
            return null;
        }

        final int port;
        try {
            port = Integer.parseInt(split[4]);
        } catch (NumberFormatException e) {
            log.debug("Not able to parse contents from S3-object '{}'", key);
            return null;
        }

        return new ClusterNodeAddress(host, port);
    }

    @Override
    public void destroy() {
        try {
            s3.deleteObject(bucketName, objectKey);
        } catch (Exception e) {
            log.error("Not able to delete object from S3");
            log.debug("Original exception", e);
        }
    }

}
