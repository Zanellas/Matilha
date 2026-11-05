package com.matilha.account;

import org.springframework.boot.SpringApplication;

/**
 * Roda o account na máquina com o PostgreSQL dos Testcontainers, sem precisar do compose.
 * Útil para experimentar: rode o main desta classe pela IDE. Mesmo padrão do matilha-transfer e do matilha-notification.
 *
 * Sem perfil, o banco sobe vazio. Para ter as contas de exemplo (10001 a 10005) e o Swagger, passe
 * --spring.profiles.active=dev nos argumentos do programa.
 */
public class TestAccountApplication {

	public static void main(String[] args) {
		SpringApplication.from(AccountApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
