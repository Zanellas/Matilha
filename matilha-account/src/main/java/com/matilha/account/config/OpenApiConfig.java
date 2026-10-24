package com.matilha.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI accountOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Matilha Account API")
                .version("v1")
                .description("Gerenciamento de contas"));
    }
}
