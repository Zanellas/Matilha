package com.matilha.transfer;

import org.springframework.boot.SpringApplication;

/**
 * Roda o transfer na máquina com o PostgreSQL dos Testcontainers, sem precisar do banco do compose.
 * Útil para experimentar: rode o main desta classe pela IDE. Mesmo padrão do matilha-account e do matilha-notification.
 *
 * O TestcontainersConfiguration só tem o PostgreSQL. Para uma transferência chegar ao fim, o account precisa estar
 * em localhost:8081 e o Kafka em localhost:9092 (por exemplo, docker compose up -d account kafka na raiz).
 * Sem o account, as transferências ficam PENDING. Sem o Kafka, os eventos esperam no outbox.
 */
public class TestTransferApplication {

	public static void main(String[] args) {
		SpringApplication.from(TransferApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
