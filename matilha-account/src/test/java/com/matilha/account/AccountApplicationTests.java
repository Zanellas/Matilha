package com.matilha.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Sobe a aplicação inteira contra um PostgreSQL real, com as migrações do Flyway e ddl-auto: validate.
 * Precisa do Docker.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountApplicationTests {

	@Test
	void contextLoads() {
	}

}
