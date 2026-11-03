package com.matilha.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI notificationOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Matilha Notification API")
                .version("v1")
                .description("Consulta de notificações"));
    }
}
