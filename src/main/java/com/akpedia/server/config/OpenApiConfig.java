package com.akpedia.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * Describes the API served at {@code /swagger-ui.html}.
 *
 * <p>The description names the service behind the embedding routes on purpose: someone
 * poking at the UI and getting a 503 should be able to tell, without reading the code,
 * which container they need to bring up.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI akpediaOpenApi() {
        return new OpenAPI().info(new Info()
                .title("akpedia-server")
                .version("0.0.1-SNAPSHOT")
                .description("""
                        Backend do Akpedia.

                        The embedding routes forward requests to the akpedia-ml embedding service
                        and return its complete response. A 503 means that service is unavailable."""));
    }

}
