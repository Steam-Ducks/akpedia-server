package com.akpedia.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The {@code JavaMailSender} itself comes from Spring Boot, built from {@code spring.mail.*}. */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {
}
