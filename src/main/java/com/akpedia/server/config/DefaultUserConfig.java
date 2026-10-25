package com.akpedia.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DefaultUserProperties.class)
public class DefaultUserConfig {
}
