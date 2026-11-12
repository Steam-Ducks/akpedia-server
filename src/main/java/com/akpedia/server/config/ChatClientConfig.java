package com.akpedia.server.config;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} the chat client talks through: akpedia-ml's address, with the
 * chat's own timeouts (see {@link ChatProperties} for why they are not the embeddings').
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public RestClient chatRestClient(
            RestClient.Builder builder, EmbeddingProperties embedding, ChatProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(embedding.baseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

}
