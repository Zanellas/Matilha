package com.matilha.notification;

import org.springframework.boot.SpringApplication;

/**
 * Roda o notification na máquina com o PostgreSQL e o Kafka dos Testcontainers, sem precisar do compose.
 * Útil para experimentar: rode o main desta classe pela IDE.
 */
public class TestNotificationApplication {

	public static void main(String[] args) {
		SpringApplication.from(NotificationApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
