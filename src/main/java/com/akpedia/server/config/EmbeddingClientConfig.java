package com.akpedia.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} the embedding client talks through.
 *
 * <p>The timeouts are the whole point of this class. A client with no read timeout waits
 * forever on a service that accepted the connection and then stopped answering, which is
 * how a single stuck call turns into an exhausted request thread pool here.
 */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingClientConfig {

    @Bean
    public RestClient embeddingRestClient(RestClient.Builder builder, EmbeddingProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

}
