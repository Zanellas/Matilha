package com.matilha.template.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.matilha.template.repository.ExampleRepository;

/**
 * Roda contra um PostgreSQL real com as migrações do Flyway, por isso precisa do Docker.
 * Se a migração não bater com a entidade, o contexto nem sobe (ddl-auto: validate).
 */
@SpringBootTest
@Testcontainers
class ExamplePersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    private ExampleRepository exampleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        exampleRepository.deleteAll();
    }

    @Nested
    class Mapping {

        @Test
        void shouldPersistAndLoadExample() {
            Example saved = exampleRepository.save(new Example("Primeiro", "Um exemplo"));

            Example loaded = exampleRepository.findById(saved.getId()).orElseThrow();

            assertThat(loaded.getName()).isEqualTo("Primeiro");
            assertThat(loaded.getDescription()).isEqualTo("Um exemplo");
            assertThat(loaded.getCreatedAt()).isNotNull();
            assertThat(loaded.getVersion()).isZero();
        }

        @Test
        void shouldCheckIfNameExists() {
            exampleRepository.save(new Example("Primeiro", null));

            assertThat(exampleRepository.existsByName("Primeiro")).isTrue();
            assertThat(exampleRepository.existsByName("Segundo")).isFalse();
        }

        @Test
        void shouldRejectDuplicateName() {
            exampleRepository.save(new Example("Primeiro", null));

            assertThatThrownBy(() -> exampleRepository.saveAndFlush(new Example("Primeiro", null)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_examples_name");
        }
    }

    @Nested
    class Constraints {

        private void insert(String name) {
            jdbcTemplate.update("INSERT INTO examples (id, name, created_at) VALUES (?, ?, now())",
                    UUID.randomUUID(), name);
        }

        @Test
        void shouldRejectBlankName() {
            assertThatThrownBy(() -> insert("   "))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_examples_name_not_blank");
        }

        @Test
        void shouldRejectMissingName() {
            assertThatThrownBy(() -> insert(null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
