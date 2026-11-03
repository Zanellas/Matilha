package com.matilha.transfer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Um PostgreSQL de verdade para os testes, na mesma versão do compose (postgres:17).
 * Quem precisa do banco sobe com @Import(TestcontainersConfiguration.class); o Spring reaproveita o container
 * entre as classes de teste que usam a mesma configuração. Mesmo padrão do matilha-account e do matilha-notification.
 *
 * O Kafka não entra: nenhum teste daqui publica de verdade (o OutboxPublisher é testado com o KafkaTemplate mockado),
 * e o matilha.reconciler.enabled=false desliga o agendamento. Por isso as classes que sobem o contexto também usam
 * spring.kafka.admin.auto-create=false: sem isso, o KafkaAdmin tenta criar o tópico na subida, procura um broker
 * em localhost:9092 e o teste passa a depender de um Kafka rodando na máquina (e trava sem ele, como no CI).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:17");
	}

}
