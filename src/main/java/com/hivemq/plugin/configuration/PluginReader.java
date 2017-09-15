/*
 * Copyright 2015 dc-square GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hivemq.plugin.configuration;

import com.google.inject.Inject;
import com.hivemq.spi.config.SystemInformation;
import com.hivemq.spi.exceptions.UnrecoverableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * @author Christian Götz
 */
public class PluginReader {


    private static final Logger log = LoggerFactory.getLogger(PluginReader.class);

    private final Properties properties = new Properties();
    private final SystemInformation systemInformation;

    @Inject
    PluginReader(SystemInformation systemInformation) {
        this.systemInformation = systemInformation;
    }

    @PostConstruct
    public void postConstruct() {
        final File configFolder = systemInformation.getConfigFolder();

        final File pluginFile = new File(configFolder, "s3discovery.properties");

        if (!pluginFile.canRead()) {
            log.error("Critical Error: Configuration file {} for S3-discovery-plugin could not be loaded. Shitting down HiveMQ", pluginFile.getAbsolutePath());
            throw new UnrecoverableException(false);
        }

        try (InputStream is = new FileInputStream(pluginFile)) {

            log.debug("Reading property file {}", pluginFile.getAbsolutePath());
            properties.load(is);
        } catch (Exception e) {
            log.error("An error occurred while reading the properties file {}", pluginFile.getAbsolutePath(), e);
        }
    }

    Properties getProperties() {
        return properties;
    }

}