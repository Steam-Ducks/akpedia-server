package com.akpedia.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} the Gotenberg client talks through.
 *
 * <p>Kept apart from the embedding service's own client: the two point at different hosts
 * and tolerate very different response times, so sharing one {@link RestClient} would mean
 * one timeout value serving both badly.
 */
@Configuration
@EnableConfigurationProperties(GotenbergProperties.class)
public class GotenbergClientConfig {

    @Bean
    public RestClient gotenbergRestClient(RestClient.Builder builder, GotenbergProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

}
