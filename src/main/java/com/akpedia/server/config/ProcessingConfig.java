package com.akpedia.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables scheduling for the retry of documents approved but never enqueued for indexing. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ProcessingProperties.class)
public class ProcessingConfig {
}
