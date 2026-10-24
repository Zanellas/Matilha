package com.matilha.transfer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI transferOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Matilha Transfer API")
                .version("v1")
                .description("Transferências entre contas"));
    }
}
