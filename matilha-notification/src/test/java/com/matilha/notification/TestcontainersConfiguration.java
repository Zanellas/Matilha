package com.matilha.notification;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Um PostgreSQL e um Kafka de verdade para os testes. As versões são as mesmas do compose
 * (postgres:17 e apache/kafka:4.3.1), para o teste rodar contra o que roda no ambiente.
 * Quem quiser os dois sobe com @Import(TestcontainersConfiguration.class); o Spring reaproveita os containers
 * entre as classes de teste que usam a mesma configuração.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer("apache/kafka:4.3.1");
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:17");
	}

}
