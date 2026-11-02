package com.matilha.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Sobe a aplicação inteira contra um PostgreSQL e um Kafka reais, com as migrações do Flyway e ddl-auto: validate.
 * Precisa do Docker.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationApplicationTests {

	@Test
	void contextLoads() {
	}

}
