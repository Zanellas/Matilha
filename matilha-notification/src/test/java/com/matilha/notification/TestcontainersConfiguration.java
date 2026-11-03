package com.matilha.notification;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.matilha.notification.config.KafkaConfig;

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

	/**
	 * Em produção quem cria o tópico é o transfer, que é o dono dele. Aqui não tem transfer,
	 * então o teste cria, com as mesmas 3 partições.
	 */
	@Bean
	NewTopic transferEventsTopic() {
		return TopicBuilder.name(KafkaConfig.TRANSFER_EVENTS_TOPIC).partitions(3).replicas(1).build();
	}

}
