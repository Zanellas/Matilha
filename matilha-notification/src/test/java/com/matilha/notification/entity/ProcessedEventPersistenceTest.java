package com.matilha.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.matilha.notification.TestcontainersConfiguration;
import com.matilha.notification.repository.ProcessedEventRepository;

/**
 * A chave primária de processed_events é a última defesa contra notificação duplicada.
 * Sem transação no teste: cada chamada ao repositório faz o próprio commit, como dois consumidores separados.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProcessedEventPersistenceTest {

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldSaveWithProcessedAt() {
        UUID eventId = UUID.randomUUID();

        processedEventRepository.save(new ProcessedEvent(eventId));

        assertThat(processedEventRepository.findById(eventId))
                .get()
                .extracting(ProcessedEvent::getProcessedAt)
                .isNotNull();
    }

    /**
     * A corrida da Etapa 3: o outro consumidor já gravou e fez commit depois do nosso existsById.
     * O nosso save precisa bater na chave primária. Se o save() fizer merge (SELECT antes do INSERT),
     * ele encontra a linha, não falha, e as notificações duplicadas são gravadas.
     */
    @Test
    void shouldRejectSecondEventWithSameId() {
        UUID eventId = UUID.randomUUID();
        processedEventRepository.save(new ProcessedEvent(eventId));

        assertThatThrownBy(() -> processedEventRepository.saveAndFlush(new ProcessedEvent(eventId)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
    }
}
