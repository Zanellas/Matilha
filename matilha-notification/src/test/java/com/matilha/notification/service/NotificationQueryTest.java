package com.matilha.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.matilha.notification.TestcontainersConfiguration;
import com.matilha.notification.dto.NotificationResponseDto;
import com.matilha.notification.entity.Notification;
import com.matilha.notification.entity.NotificationType;
import com.matilha.notification.repository.NotificationRepository;

/**
 * A consulta contra o PostgreSQL de verdade. O banco é compartilhado com os testes do consumidor,
 * então cada teste usa contas sorteadas na faixa 7xxxx, que os outros testes não usam.
 * A ordem padrão vem do PageableConfig, na camada web; aqui o service recebe o Pageable já pronto,
 * por isso os testes passam a mesma ordem que o PageableConfig usa.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationQueryTest {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final Instant OLDEST = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant MIDDLE = Instant.parse("2026-11-01T11:00:00Z");
    private static final Instant NEWEST = Instant.parse("2026-11-01T12:00:00Z");

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String newAccount() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(70_000, 80_000));
    }

    private static PageRequest newestFirst(int page, int size) {
        return PageRequest.of(page, size, NEWEST_FIRST);
    }

    /**
     * O created_at vem do @CreationTimestamp, que não dá para escolher. O UPDATE direto fixa o horário,
     * para a ordem do teste não depender de duas gravações caírem em instantes diferentes.
     */
    private void save(String account, String message, Instant createdAt) {
        Notification notification = notificationRepository.saveAndFlush(new Notification(UUID.randomUUID(),
                UUID.randomUUID(), account, NotificationType.TRANSFER_SENT, message, null));
        jdbcTemplate.update("UPDATE notifications SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt), notification.getId());
    }

    @Test
    void shouldReturnOnlyNotificationsOfTheAccount() {
        String account = newAccount();
        String other = String.valueOf(Integer.parseInt(account) + 1);
        save(account, "mine", NEWEST);
        save(other, "not mine", NEWEST);

        Page<NotificationResponseDto> page = notificationService.findByAccount(account, newestFirst(0, 10));

        assertThat(page.getContent())
                .extracting(NotificationResponseDto::message)
                .containsExactly("mine");
    }

    @Test
    void shouldReturnNewestFirst() {
        String account = newAccount();
        save(account, "middle", MIDDLE);
        save(account, "newest", NEWEST);
        save(account, "oldest", OLDEST);

        Page<NotificationResponseDto> page = notificationService.findByAccount(account, newestFirst(0, 10));

        assertThat(page.getContent())
                .extracting(NotificationResponseDto::message)
                .containsExactly("newest", "middle", "oldest");
    }

    /** Como no account, o cliente pode pedir outra ordem com ?sort=; o service respeita o que vier. */
    @Test
    void shouldRespectRequestedSort() {
        String account = newAccount();
        save(account, "z-oldest", OLDEST);
        save(account, "a-newest", NEWEST);

        Page<NotificationResponseDto> page = notificationService.findByAccount(account,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "message")));

        assertThat(page.getContent())
                .extracting(NotificationResponseDto::message)
                .containsExactly("z-oldest", "a-newest");
    }

    @Test
    void shouldPaginate() {
        String account = newAccount();
        save(account, "oldest", OLDEST);
        save(account, "middle", MIDDLE);
        save(account, "newest", NEWEST);

        Page<NotificationResponseDto> first = notificationService.findByAccount(account, newestFirst(0, 2));
        Page<NotificationResponseDto> second = notificationService.findByAccount(account, newestFirst(1, 2));

        assertThat(first.getContent()).extracting(NotificationResponseDto::message).containsExactly("newest", "middle");
        assertThat(second.getContent()).extracting(NotificationResponseDto::message).containsExactly("oldest");
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
    }

    @Test
    void shouldMapAllFieldsToTheResponse() {
        String account = newAccount();
        save(account, "Você enviou 250.50 para a conta 10002", NEWEST);

        NotificationResponseDto response = notificationService.findByAccount(account, newestFirst(0, 10))
                .getContent().getFirst();

        assertThat(response.id()).isNotNull();
        assertThat(response.transferId()).isNotNull();
        assertThat(response.accountNumber()).isEqualTo(account);
        assertThat(response.type()).isEqualTo(NotificationType.TRANSFER_SENT);
        assertThat(response.message()).isEqualTo("Você enviou 250.50 para a conta 10002");
        assertThat(response.failureCode()).isNull();
        assertThat(response.createdAt()).isEqualTo(NEWEST);
    }

    @Test
    void shouldReturnEmptyPageForUnknownAccount() {
        Page<NotificationResponseDto> page = notificationService.findByAccount("00000", newestFirst(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }
}
