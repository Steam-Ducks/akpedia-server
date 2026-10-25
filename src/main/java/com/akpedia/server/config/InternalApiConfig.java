package com.akpedia.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Guards the {@code /internal} routes, which only the akpedia-ml worker calls, with a shared token. */
@Configuration
@EnableConfigurationProperties(InternalApiProperties.class)
public class InternalApiConfig {

    private static final Logger LOG = LoggerFactory.getLogger(InternalApiConfig.class);

    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilter(
            InternalApiProperties properties, ObjectMapper json) {
        if (!properties.hasToken()) {
            LOG.warn("INTERNAL_API_TOKEN nao definido: as rotas /internal vao recusar toda chamada, "
                    + "e nenhum documento aprovado sera indexado.");
        }
        FilterRegistrationBean<InternalTokenFilter> registration =
                new FilterRegistrationBean<>(new InternalTokenFilter(properties, json));
        registration.addUrlPatterns("/internal/*");
        return registration;
    }
}
