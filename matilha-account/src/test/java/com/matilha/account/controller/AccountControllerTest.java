package com.matilha.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.matilha.account.config.PageableConfig;
import com.matilha.account.config.ProblemDetailSecurityHandler;
import com.matilha.account.config.SecurityConfig;
import com.matilha.account.dto.AccountResponseDto;
import com.matilha.account.dto.AccountSummaryDto;
import com.matilha.account.dto.CreateAccountRequestDto;
import com.matilha.account.dto.UpdateAccountRequestDto;
import com.matilha.account.entity.AccountStatus;
import com.matilha.account.exception.AccountNotFoundException;
import com.matilha.account.exception.BusinessException;
import com.matilha.account.service.AccountService;

/**
 * O @WebMvcTest não carrega classes @Configuration: o PageableConfig e o SecurityConfig entram pelo @Import,
 * para valerem a paginação e as regras de segurança de verdade.
 *
 * As requisições levam um JWT pelo jwt() do spring-security-test, que pula a validação da assinatura.
 * A seção Security usa o JwtDecoder mockado com um header Bearer, para passar pelo conversor de papéis do Keycloak.
 */
@WebMvcTest(AccountController.class)
@Import({ PageableConfig.class, SecurityConfig.class, ProblemDetailSecurityHandler.class })
class AccountControllerTest {

    private static final String ANA = "00000000-0000-0000-0003-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject(ANA)).authorities(new SimpleGrantedAuthority("ROLE_customer"));
    }

    private static JwtRequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("00000000-0000-0000-0003-000000000003"))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"));
    }

    private static Jwt keycloakToken(Map<String, Object> claims) {
        Jwt.Builder token = Jwt.withTokenValue("keycloak-token")
                .header("alg", "RS256")
                .subject(ANA)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(token::claim);
        return token.build();
    }

    private static AccountResponseDto responseFor(String accountNumber, String holderName, AccountStatus status) {
        return new AccountResponseDto(accountNumber, holderName, "11987654321", "ana.silva@example.com", status,
                new BigDecimal("150.75"));
    }

    @Nested
    class Create {

        private static final String VALID_REQUEST = """
                {
                  "holderName": "Ana Silva",
                  "holderDocument": "529.982.247-25",
                  "holderEmail": "ana.silva@example.com",
                  "holderPhone": "(11) 98765-4321"
                }
                """;

        private static String requestWith(String field, String value) {
            return VALID_REQUEST.replaceFirst("\"" + field + "\": \"[^\"]*\"", "\"" + field + "\": \"" + value + "\"");
        }

        @Test
        void shouldReturnCreatedWithAccount() throws Exception {
            CreateAccountRequestDto request = new CreateAccountRequestDto(
                    "Ana Silva", "529.982.247-25", "ana.silva@example.com", "(11) 98765-4321");
            when(accountService.create(request, ANA)).thenReturn(responseFor("12345", "Ana Silva", AccountStatus.ACTIVE));

            mockMvc.perform(post("/accounts").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accountNumber").value("12345"))
                    .andExpect(jsonPath("$.holderName").value("Ana Silva"))
                    .andExpect(jsonPath("$.holderPhone").value("11987654321"))
                    .andExpect(jsonPath("$.holderEmail").value("ana.silva@example.com"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.balance").value(150.75))
                    .andExpect(jsonPath("$.holderDocument").doesNotExist());
        }

        /**
         * O dono vem do sub do token, nunca do corpo (mass assignment, OWASP API3:2023).
         * A Ana manda o id do Bruno no JSON: o campo é ignorado e a conta fica com o dono do token.
         */
        @Test
        void shouldTakeOwnerFromTokenAndIgnoreOwnerInBody() throws Exception {
            when(accountService.create(any(), any())).thenReturn(responseFor("12345", "Ana Silva", AccountStatus.ACTIVE));

            mockMvc.perform(post("/accounts").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST.replace("{", "{\n  \"ownerId\": \"00000000-0000-0000-0003-000000000002\",")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ownerId").doesNotExist());

            verify(accountService).create(
                    new CreateAccountRequestDto("Ana Silva", "529.982.247-25", "ana.silva@example.com", "(11) 98765-4321"),
                    ANA);
        }

        @Test
        void shouldAcceptUnformattedCpfAndPhone() throws Exception {
            when(accountService.create(any(), any())).thenReturn(responseFor("12345", "Ana Silva", AccountStatus.ACTIVE));

            mockMvc.perform(post("/accounts").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("holderDocument", "52998224725")
                            .replace("(11) 98765-4321", "11987654321")))
                    .andExpect(status().isCreated());
        }

        @Test
        void shouldReturnBadRequestWithFieldErrorsWhenFieldsAreBlank() throws Exception {
            mockMvc.perform(post("/accounts").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"holderName": "", "holderDocument": " ", "holderEmail": "", "holderPhone": " "}
                            """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail").value("Invalid request"))
                    .andExpect(jsonPath("$.errors.holderName").exists())
                    .andExpect(jsonPath("$.errors.holderDocument").exists())
                    .andExpect(jsonPath("$.errors.holderEmail").exists())
                    .andExpect(jsonPath("$.errors.holderPhone").exists());

            verify(accountService, never()).create(any(), any());
        }

        @Test
        void shouldReturnBadRequestWhenFieldsAreMissing() throws Exception {
            mockMvc.perform(post("/accounts").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.holderName").exists())
                    .andExpect(jsonPath("$.errors.holderDocument").exists())
                    .andExpect(jsonPath("$.errors.holderEmail").exists())
                    .andExpect(jsonPath("$.errors.holderPhone").exists());

            verify(accountService, never()).create(any(), any());
        }

        @ParameterizedTest
        @ValueSource(strings = { "529.982.247-26", "111.111.111-11", "12345678900", "529982247-25" })
        void shouldReturnBadRequestWhenCpfIsInvalid(String cpf) throws Exception {
            mockMvc.perform(post("/accounts").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("holderDocument", cpf)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.holderDocument").exists());

            verify(accountService, never()).create(any(), any());
        }

        @ParameterizedTest
        @ValueSource(strings = { "ana.silva", "ana@", "@example.com" })
        void shouldReturnBadRequestWhenEmailIsInvalid(String email) throws Exception {
            mockMvc.perform(post("/accounts").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("holderEmail", email)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.holderEmail").exists());

            verify(accountService, never()).create(any(), any());
        }

        @ParameterizedTest
        @ValueSource(strings = { "98765-4321", "(11) 98765-43210", "abc", "+55 11 98765-4321" })
        void shouldReturnBadRequestWhenPhoneIsInvalid(String phone) throws Exception {
            mockMvc.perform(post("/accounts").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("holderPhone", phone)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.holderPhone").exists());

            verify(accountService, never()).create(any(), any());
        }

        @Test
        void shouldReturnBadRequestWhenJsonIsMalformed() throws Exception {
            mockMvc.perform(post("/accounts").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"holderName\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(accountService, never()).create(any(), any());
        }
    }

    @Nested
    class FindByAccountNumber {

        @Test
        void shouldReturnAccount() throws Exception {
            when(accountService.findByAccountNumber("12345")).thenReturn(responseFor("12345", "Ana Silva", AccountStatus.ACTIVE));

            mockMvc.perform(get("/accounts/12345").with(customer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountNumber").value("12345"))
                    .andExpect(jsonPath("$.holderName").value("Ana Silva"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        void shouldReturnNotFoundWhenAccountDoesNotExist() throws Exception {
            when(accountService.findByAccountNumber("99999")).thenThrow(new AccountNotFoundException("99999"));

            mockMvc.perform(get("/accounts/99999").with(customer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.detail").value("Account 99999 not found"));
        }
    }

    @Nested
    class FindAll {

        @Test
        void shouldReturnPagedAccounts() throws Exception {
            Pageable pageable = PageRequest.of(0, 2);
            List<AccountSummaryDto> accounts = List.of(
                    new AccountSummaryDto("11111", "Ana Silva", AccountStatus.ACTIVE),
                    new AccountSummaryDto("22222", "Bruno Costa", AccountStatus.BLOCKED));
            when(accountService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(accounts, pageable, 5));

            mockMvc.perform(get("/accounts").with(customer()).param("page", "0").param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.content[0].accountNumber").value("11111"))
                    .andExpect(jsonPath("$.content[0].holderName").value("Ana Silva"))
                    .andExpect(jsonPath("$.content[1].status").value("BLOCKED"))
                    .andExpect(jsonPath("$.page.size").value(2))
                    .andExpect(jsonPath("$.page.number").value(0))
                    .andExpect(jsonPath("$.page.totalElements").value(5))
                    .andExpect(jsonPath("$.page.totalPages").value(3));
        }

        @Test
        void shouldNotExposeContactDataOrBalanceInListing() throws Exception {
            List<AccountSummaryDto> accounts = List.of(new AccountSummaryDto("11111", "Ana Silva", AccountStatus.ACTIVE));
            when(accountService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(accounts));

            mockMvc.perform(get("/accounts").with(customer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].holderEmail").doesNotExist())
                    .andExpect(jsonPath("$.content[0].holderPhone").doesNotExist())
                    .andExpect(jsonPath("$.content[0].holderDocument").doesNotExist())
                    .andExpect(jsonPath("$.content[0].balance").doesNotExist());
        }

        @Test
        void shouldPassPaginationParametersToService() throws Exception {
            when(accountService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/accounts").with(customer()).param("page", "2").param("size", "5"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(accountService).findAll(captor.capture());
            assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
            assertThat(captor.getValue().getPageSize()).isEqualTo(5);
        }

        /** Sem parâmetros vale o PageableConfig: primeira página, 20 itens, mais recente primeiro. */
        @Test
        void shouldUseDefaultsFromPageableConfig() throws Exception {
            when(accountService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/accounts").with(customer()))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(accountService).findAll(captor.capture());
            assertThat(captor.getValue().getPageNumber()).isZero();
            assertThat(captor.getValue().getPageSize()).isEqualTo(20);
            assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        }

        /**
         * Como o PageableConfig declara o próprio customizer, o do Spring Boot não é criado e o
         * spring.data.web.pageable.max-page-size do application.yml é ignorado: o limite precisa estar no PageableConfig.
         */
        @Test
        void shouldCapPageSizeAt100() throws Exception {
            when(accountService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/accounts").with(customer()).param("size", "5000"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(accountService).findAll(captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        }
    }

    @Nested
    class Update {

        @Test
        void shouldReturnUpdatedAccount() throws Exception {
            UpdateAccountRequestDto request = new UpdateAccountRequestDto("Ana Souza", null, null);
            when(accountService.update("12345", request)).thenReturn(responseFor("12345", "Ana Souza", AccountStatus.ACTIVE));

            mockMvc.perform(patch("/accounts/12345").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"holderName": "Ana Souza"}
                            """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountNumber").value("12345"))
                    .andExpect(jsonPath("$.holderName").value("Ana Souza"));
        }

        @Test
        void shouldPassAllProvidedFieldsToService() throws Exception {
            when(accountService.update(anyString(), any())).thenReturn(responseFor("12345", "Ana Souza", AccountStatus.ACTIVE));

            mockMvc.perform(patch("/accounts/12345").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"holderName": "Ana Souza", "holderEmail": "ana.souza@example.com", "holderPhone": "(21) 98765-4321"}
                            """))
                    .andExpect(status().isOk());

            verify(accountService).update("12345",
                    new UpdateAccountRequestDto("Ana Souza", "ana.souza@example.com", "(21) 98765-4321"));
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   " })
        void shouldReturnBadRequestWhenNameIsBlank(String holderName) throws Exception {
            mockMvc.perform(patch("/accounts/12345").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"holderName\": \"" + holderName + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.holderName").exists());

            verify(accountService, never()).update(anyString(), any());
        }

        @Test
        void shouldReturnBadRequestWhenEmailIsInvalid() throws Exception {
            mockMvc.perform(patch("/accounts/12345").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"holderEmail": "ana.souza"}
                            """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.holderEmail").exists());

            verify(accountService, never()).update(anyString(), any());
        }

        @Test
        void shouldReturnBadRequestWhenPhoneIsInvalid() throws Exception {
            mockMvc.perform(patch("/accounts/12345").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"holderPhone": "98765-4321"}
                            """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.holderPhone").exists());

            verify(accountService, never()).update(anyString(), any());
        }

        @Test
        void shouldReturnNotFoundWhenAccountDoesNotExist() throws Exception {
            when(accountService.update(eq("99999"), any())).thenThrow(new AccountNotFoundException("99999"));

            mockMvc.perform(patch("/accounts/99999").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"holderName": "Ana Souza"}
                            """))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Account 99999 not found"));
        }

        @Test
        void shouldReturnUnprocessableContentWhenAccountIsClosed() throws Exception {
            when(accountService.update(eq("12345"), any()))
                    .thenThrow(new BusinessException("Closed accounts cannot be updated"));

            mockMvc.perform(patch("/accounts/12345").with(customer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"holderName": "Ana Souza"}
                            """))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail").value("Closed accounts cannot be updated"));
        }
    }

    @Nested
    class Close {

        @Test
        void shouldReturnNoContent() throws Exception {
            mockMvc.perform(delete("/accounts/12345").with(customer()))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));

            verify(accountService).close("12345");
        }

        @Test
        void shouldReturnNotFoundWhenAccountDoesNotExist() throws Exception {
            doThrow(new AccountNotFoundException("99999")).when(accountService).close("99999");

            mockMvc.perform(delete("/accounts/99999").with(customer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Account 99999 not found"));
        }

        @Test
        void shouldReturnUnprocessableContentWhenBalanceIsNotZero() throws Exception {
            doThrow(new BusinessException("Account balance must be zero to close"))
                    .when(accountService).close("12345");

            mockMvc.perform(delete("/accounts/12345").with(customer()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail").value("Account balance must be zero to close"));
        }
    }

    @Nested
    class Block {

        @Test
        void shouldReturnBlockedAccount() throws Exception {
            when(accountService.block("12345")).thenReturn(responseFor("12345", "Ana Silva", AccountStatus.BLOCKED));

            mockMvc.perform(post("/accounts/12345/block").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountNumber").value("12345"))
                    .andExpect(jsonPath("$.status").value("BLOCKED"));

            verify(accountService).block("12345");
            verify(accountService, never()).unblock(anyString());
        }

        @Test
        void shouldReturnUnprocessableContentWhenAccountIsNotActive() throws Exception {
            when(accountService.block("12345"))
                    .thenThrow(new BusinessException("Only active accounts can be blocked"));

            mockMvc.perform(post("/accounts/12345/block").with(admin()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail").value("Only active accounts can be blocked"));
        }

        @Test
        void shouldReturnNotFoundWhenAccountDoesNotExist() throws Exception {
            when(accountService.block("99999")).thenThrow(new AccountNotFoundException("99999"));

            mockMvc.perform(post("/accounts/99999/block").with(admin()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Account 99999 not found"));
        }
    }

    @Nested
    class Unblock {

        @Test
        void shouldReturnActiveAccount() throws Exception {
            when(accountService.unblock("12345")).thenReturn(responseFor("12345", "Ana Silva", AccountStatus.ACTIVE));

            mockMvc.perform(post("/accounts/12345/unblock").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountNumber").value("12345"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            verify(accountService).unblock("12345");
            verify(accountService, never()).block(anyString());
        }

        @Test
        void shouldReturnUnprocessableContentWhenAccountIsNotBlocked() throws Exception {
            when(accountService.unblock("12345"))
                    .thenThrow(new BusinessException("Only blocked accounts can be unblocked"));

            mockMvc.perform(post("/accounts/12345/unblock").with(admin()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail").value("Only blocked accounts can be unblocked"));
        }

        @Test
        void shouldReturnNotFoundWhenAccountDoesNotExist() throws Exception {
            when(accountService.unblock("99999")).thenThrow(new AccountNotFoundException("99999"));

            mockMvc.perform(post("/accounts/99999/unblock").with(admin()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Account 99999 not found"));
        }
    }

    @Nested
    class ExceptionHandling {

        @Test
        void shouldReturnUnprocessableContentForBusinessException() throws Exception {
            when(accountService.findByAccountNumber("12345")).thenThrow(new BusinessException("Insufficient balance"));

            mockMvc.perform(get("/accounts/12345").with(customer()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail").value("Insufficient balance"));
        }

        @Test
        void shouldHideInternalDetailsForUnexpectedErrors() throws Exception {
            when(accountService.findByAccountNumber("12345"))
                    .thenThrow(new RuntimeException("SELECT * FROM accounts -- connection refused"));

            mockMvc.perform(get("/accounts/12345").with(customer()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                    .andExpect(content().string(not(containsString("SELECT"))));
        }
    }

    @Nested
    class Security {

        /** Sem token, nem pela porta do gateway nem direto na 8081. */
        @Test
        void shouldReturnUnauthorizedWithoutToken() throws Exception {
            mockMvc.perform(get("/accounts/12345"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

            verify(accountService, never()).findByAccountNumber(anyString());
        }

        /** Sem token, o POST recebe 401 e não 403: o CSRF está desligado, porque não existe sessão nem cookie. */
        @Test
        void shouldReturnUnauthorizedForPostWithoutToken() throws Exception {
            mockMvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }

        @Test
        void shouldNotCreateHttpSession() throws Exception {
            when(jwtDecoder.decode("keycloak-token")).thenReturn(keycloakToken(Map.of()));
            when(accountService.findByAccountNumber("12345")).thenReturn(responseFor("12345", "Ana Silva", AccountStatus.ACTIVE));

            mockMvc.perform(get("/accounts/12345").header(HttpHeaders.AUTHORIZATION, "Bearer keycloak-token"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                    .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
        }

        @ParameterizedTest
        @ValueSource(strings = { "/accounts/12345/block", "/accounts/12345/unblock" })
        void shouldReturnForbiddenWhenCustomerBlocksOrUnblocks(String path) throws Exception {
            mockMvc.perform(post(path).with(customer()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verify(accountService, never()).block(anyString());
            verify(accountService, never()).unblock(anyString());
        }

        @ParameterizedTest
        @ValueSource(strings = { "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness" })
        void shouldNotRequireTokenForHealth(String path) throws Exception {
            mockMvc.perform(get(path))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
        }
    }

    /**
     * O token passa pelo JwtDecoder (mockado) e pelo conversor do SecurityConfig, como um token real do Keycloak:
     * o papel vem de realm_access.roles e vira a authority ROLE_<papel>.
     */
    @Nested
    class KeycloakRoles {

        @Test
        void shouldAllowBlockWhenRealmRolesContainAdmin() throws Exception {
            when(jwtDecoder.decode("keycloak-token")).thenReturn(keycloakToken(
                    Map.of("realm_access", Map.of("roles", List.of("admin", "default-roles-matilha")))));
            when(accountService.block("12345")).thenReturn(responseFor("12345", "Ana Silva", AccountStatus.BLOCKED));

            mockMvc.perform(post("/accounts/12345/block").header(HttpHeaders.AUTHORIZATION, "Bearer keycloak-token"))
                    .andExpect(status().isOk());

            verify(accountService).block("12345");
        }

        @Test
        void shouldForbidBlockWhenRealmRolesContainOnlyCustomer() throws Exception {
            when(jwtDecoder.decode("keycloak-token")).thenReturn(keycloakToken(
                    Map.of("realm_access", Map.of("roles", List.of("customer", "default-roles-matilha")))));

            mockMvc.perform(post("/accounts/12345/block").header(HttpHeaders.AUTHORIZATION, "Bearer keycloak-token"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            verify(accountService, never()).block(anyString());
        }

        /** Papel do Keycloak não é authority do Spring: "admin" na claim scope não vira ROLE_admin. */
        @Test
        void shouldIgnoreAdminOutsideRealmAccess() throws Exception {
            when(jwtDecoder.decode("keycloak-token")).thenReturn(keycloakToken(Map.of("scope", "admin")));

            mockMvc.perform(post("/accounts/12345/block").header(HttpHeaders.AUTHORIZATION, "Bearer keycloak-token"))
                    .andExpect(status().isForbidden());
        }

        /** Um token sem realm_access autentica, mas sem papel nenhum: 200 na leitura, 403 no block, e nunca 500. */
        @Test
        void shouldTreatTokenWithoutRealmAccessAsNoRoles() throws Exception {
            when(jwtDecoder.decode("keycloak-token")).thenReturn(keycloakToken(Map.of("preferred_username", "ana")));
            when(accountService.findByAccountNumber("12345")).thenReturn(responseFor("12345", "Ana Silva", AccountStatus.ACTIVE));

            mockMvc.perform(get("/accounts/12345").header(HttpHeaders.AUTHORIZATION, "Bearer keycloak-token"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/accounts/12345/block").header(HttpHeaders.AUTHORIZATION, "Bearer keycloak-token"))
                    .andExpect(status().isForbidden());
        }
    }
}
