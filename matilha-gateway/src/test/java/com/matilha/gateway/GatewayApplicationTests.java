package com.matilha.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Sobe o contexto inteiro do gateway. Não precisa do Docker: o gateway não tem banco nem Kafka.
 */
@SpringBootTest
class GatewayApplicationTests {

	@Test
	void contextLoads() {
	}

}
