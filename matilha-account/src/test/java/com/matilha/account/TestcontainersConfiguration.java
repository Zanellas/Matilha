package com.matilha.account;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Um PostgreSQL de verdade para os testes, na mesma versão do compose (postgres:17).
 * Quem precisa do banco sobe com @Import(TestcontainersConfiguration.class); o Spring reaproveita o container
 * entre as classes de teste que usam a mesma configuração. Mesmo padrão do matilha-transfer e do matilha-notification.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:17");
	}

}
