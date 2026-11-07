package com.matilha.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.matilha.notification.config.PageableConfig;
import com.matilha.notification.config.ProblemDetailSecurityHandler;
import com.matilha.notification.config.SecurityConfig;
import com.matilha.notification.dto.NotificationResponseDto;
import com.matilha.notification.entity.FailureCode;
import com.matilha.notification.entity.NotificationType;
import com.matilha.notification.service.NotificationService;

/**
 * O @WebMvcTest não carrega classes @Configuration: o PageableConfig e o SecurityConfig entram pelo @Import,
 * para valerem a ordem padrão e as regras de segurança de verdade.
 * As requisições levam um JWT pelo jwt() do spring-security-test; os testes de segurança no fim testam sem ele.
 */
@WebMvcTest(NotificationController.class)
@Import({ PageableConfig.class, SecurityConfig.class, ProblemDetailSecurityHandler.class })
class NotificationControllerTest {

    private static final String ACCOUNT = "10001";
    private static final String ANA = "00000000-0000-0000-0003-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor user() {
        return jwt().jwt(token -> token.subject(ANA));
    }

    private static NotificationResponseDto sent() {
        return new NotificationResponseDto(UUID.randomUUID(), UUID.randomUUID(), ACCOUNT,
                NotificationType.TRANSFER_SENT, "Você enviou 250.50 para a conta 10002", null,
                Instant.parse("2026-11-02T12:00:00Z"));
    }

    private static NotificationResponseDto failed() {
        return new NotificationResponseDto(UUID.randomUUID(), UUID.randomUUID(), ACCOUNT,
                NotificationType.TRANSFER_FAILED, "Sua transferência de 99.90 para a conta 10002 falhou",
                FailureCode.INSUFFICIENT_BALANCE, Instant.parse("2026-11-02T11:00:00Z"));
    }

    @Test
    void shouldReturnPagedNotifications() throws Exception {
        Pageable pageable = PageRequest.of(0, 2);
        when(notificationService.findByAccount(eq(ANA), eq(ACCOUNT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sent(), failed()), pageable, 5));

        mockMvc.perform(get("/notifications").with(user()).param("accountNumber", ACCOUNT).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].accountNumber").value(ACCOUNT))
                .andExpect(jsonPath("$.content[0].type").value("TRANSFER_SENT"))
                .andExpect(jsonPath("$.content[0].failureCode").doesNotExist())
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-11-02T12:00:00Z"))
                .andExpect(jsonPath("$.content[1].type").value("TRANSFER_FAILED"))
                .andExpect(jsonPath("$.content[1].failureCode").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.page.totalPages").value(3));
    }

    /** A API devolve o DTO, não a entidade: o eventId é detalhe interno da idempotência. */
    @Test
    void shouldNotExposeEventId() throws Exception {
        when(notificationService.findByAccount(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sent())));

        mockMvc.perform(get("/notifications").with(user()).param("accountNumber", ACCOUNT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventId").doesNotExist())
                .andExpect(jsonPath("$.content[0].ownerId").doesNotExist());
    }

    /** O dono da consulta vem do sub do token: o Bruno pedindo a mesma conta é outra consulta. */
    @Test
    void shouldPassTheTokenSubjectAsOwner() throws Exception {
        String bruno = "00000000-0000-0000-0003-000000000002";
        when(notificationService.findByAccount(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/notifications").with(jwt().jwt(token -> token.subject(bruno)))
                .param("accountNumber", "10002"))
                .andExpect(status().isOk());

        verify(notificationService).findByAccount(eq(bruno), eq("10002"), any(Pageable.class));
    }

    @Test
    void shouldPassAccountAndPaginationToService() throws Exception {
        when(notificationService.findByAccount(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/notifications").with(user()).param("accountNumber", ACCOUNT).param("page", "2").param("size", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationService).findByAccount(eq(ANA), eq(ACCOUNT), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
    }

    /** Sem parâmetros de paginação vale o PageableConfig: primeira página, 20 itens, mais recente primeiro. */
    @Test
    void shouldUseNewestFirstByDefault() throws Exception {
        when(notificationService.findByAccount(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/notifications").with(user()).param("accountNumber", ACCOUNT))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationService).findByAccount(eq(ANA), eq(ACCOUNT), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /** Pedindo só página e tamanho, a ordem padrão continua valendo. */
    @Test
    void shouldKeepDefaultSortWhenOnlyPageAndSizeAreGiven() throws Exception {
        when(notificationService.findByAccount(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/notifications").with(user()).param("accountNumber", ACCOUNT).param("page", "1").param("size", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationService).findByAccount(eq(ANA), eq(ACCOUNT), captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /**
     * Um cliente não pode pedir a tabela inteira de uma vez. Como o PageableConfig declara o próprio customizer,
     * o do Spring Boot não é criado e o max-page-size do application.yml não vale: o limite precisa estar no PageableConfig.
     */
    @Test
    void shouldCapPageSizeAt100() throws Exception {
        when(notificationService.findByAccount(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/notifications").with(user()).param("accountNumber", ACCOUNT).param("size", "5000"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationService).findByAccount(eq(ANA), eq(ACCOUNT), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    /** Mesmo formato dos outros serviços: 5 dígitos. Fora disso é erro do cliente, não uma página vazia. */
    @ParameterizedTest
    @ValueSource(strings = { "abc", "1234", "123456", "1000a", " " })
    void shouldReturnProblemDetailForInvalidAccountNumber(String accountNumber) throws Exception {
        mockMvc.perform(get("/notifications").with(user()).param("accountNumber", accountNumber))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        verify(notificationService, never()).findByAccount(anyString(), anyString(), any(Pageable.class));
    }

    /** O notification não conhece as contas: conta sem notificações é uma página vazia, não 404. */
    @Test
    void shouldReturnEmptyPageForAccountWithoutNotifications() throws Exception {
        when(notificationService.findByAccount(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/notifications").with(user()).param("accountNumber", "99999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    /**
     * Sem o accountNumber, a consulta traz as notificações de todas as contas do usuário. Só é seguro porque a
     * consulta sempre filtra pelo sub do token: antes do filtro pelo dono, isso devolveria o banco inteiro.
     */
    @Test
    void shouldReturnAllNotificationsOfTheUserWhenAccountNumberIsMissing() throws Exception {
        when(notificationService.findByAccount(eq(ANA), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sent(), failed())));

        mockMvc.perform(get("/notifications").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));

        verify(notificationService).findByAccount(eq(ANA), isNull(), any(Pageable.class));
    }

    /** Sem o accountNumber, a paginação e a ordem padrão continuam valendo. */
    @Test
    void shouldUseNewestFirstWhenAccountNumberIsMissing() throws Exception {
        when(notificationService.findByAccount(anyString(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/notifications").with(user()))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationService).findByAccount(eq(ANA), isNull(), captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void shouldReturnProblemDetailWithoutInternalsOnUnexpectedError() throws Exception {
        when(notificationService.findByAccount(anyString(), anyString(), any(Pageable.class)))
                .thenThrow(new IllegalStateException("connection to db-notification refused"));

        mockMvc.perform(get("/notifications").with(user()).param("accountNumber", ACCOUNT))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("db-notification"))));
    }

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/notifications").param("accountNumber", ACCOUNT))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verify(notificationService, never()).findByAccount(anyString(), anyString(), any(Pageable.class));
    }

    /** Sem token, a validação nem roda: o 401 vem antes do 400 do accountNumber inválido. */
    @Test
    void shouldReturnUnauthorizedBeforeValidatingParameters() throws Exception {
        mockMvc.perform(get("/notifications").param("accountNumber", "abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** O token passa pelo JwtDecoder (mockado) e pelo filtro de verdade, e nenhuma sessão HTTP é criada. */
    @Test
    void shouldAuthenticateBearerTokenWithoutCreatingSession() throws Exception {
        when(jwtDecoder.decode("keycloak-token")).thenReturn(Jwt.withTokenValue("keycloak-token")
                .header("alg", "RS256")
                .subject(ANA)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build());
        when(notificationService.findByAccount(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/notifications").param("accountNumber", ACCOUNT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer keycloak-token"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = { "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness" })
    void shouldNotRequireTokenForHealth(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }
}
