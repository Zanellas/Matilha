package com.matilha.template.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI templateOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Matilha Template API")
                .version("v1")
                .description("Template de serviço"));
    }
}
