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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.matilha.account.dto.AccountResponseDto;
import com.matilha.account.dto.CreateAccountRequestDto;
import com.matilha.account.dto.UpdateAccountRequestDto;
import com.matilha.account.entity.AccountStatus;
import com.matilha.account.exception.AccountNotFoundException;
import com.matilha.account.exception.BusinessException;
import com.matilha.account.service.AccountService;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

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
            when(accountService.create(request)).thenReturn(responseFor("12345", "Ana Silva", AccountStatus.ACTIVE));

            mockMvc.perform(post("/accounts")
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

        @Test
        void shouldAcceptUnformattedCpfAndPhone() throws Exception {
            when(accountService.create(any())).thenReturn(responseFor("12345", "Ana Silva", AccountStatus.ACTIVE));

            mockMvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("holderDocument", "52998224725")
                            .replace("(11) 98765-4321", "11987654321")))
                    .andExpect(status().isCreated());
        }

        @Test
        void shouldReturnBadRequestWithFieldErrorsWhenFieldsAreBlank() throws Exception {
            mockMvc.perform(post("/accounts")
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

            verify(accountService, never()).create(any());
        }

        @Test
        void shouldReturnBadRequestWhenFieldsAreMissing() throws Exception {
            mockMvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.holderName").exists())
                    .andExpect(jsonPath("$.errors.holderDocument").exists())
                    .andExpect(jsonPath("$.errors.holderEmail").exists())
                    .andExpect(jsonPath("$.errors.holderPhone").exists());

            verify(accountService, never()).create(any());
        }

        @ParameterizedTest
        @ValueSource(strings = { "529.982.247-26", "111.111.111-11", "12345678900", "529982247-25" })
        void shouldReturnBadRequestWhenCpfIsInvalid(String cpf) throws Exception {
            mockMvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("holderDocument", cpf)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.holderDocument").exists());

            verify(accountService, never()).create(any());
        }

        @ParameterizedTest
        @ValueSource(strings = { "ana.silva", "ana@", "@example.com" })
        void shouldReturnBadRequestWhenEmailIsInvalid(String email) throws Exception {
            mockMvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("holderEmail", email)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.holderEmail").exists());

            verify(accountService, never()).create(any());
        }

        @ParameterizedTest
        @ValueSource(strings = { "98765-4321", "(11) 98765-43210", "abc", "+55 11 98765-4321" })
        void shouldReturnBadRequestWhenPhoneIsInvalid(String phone) throws Exception {
            mockMvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("holderPhone", phone)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.holderPhone").exists());

            verify(accountService, never()).create(any());
        }

        @Test
        void shouldReturnBadRequestWhenJsonIsMalformed() throws Exception {
            mockMvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"holderName\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(accountService, never()).create(any());
        }
    }

    @Nested
    class FindByAccountNumber {

        @Test
        void shouldReturnAccount() throws Exception {
            when(accountService.findByAccountNumber("12345")).thenReturn(responseFor("12345", "Ana Silva", AccountStatus.ACTIVE));

            mockMvc.perform(get("/accounts/12345"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountNumber").value("12345"))
                    .andExpect(jsonPath("$.holderName").value("Ana Silva"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        void shouldReturnNotFoundWhenAccountDoesNotExist() throws Exception {
            when(accountService.findByAccountNumber("99999")).thenThrow(new AccountNotFoundException("99999"));

            mockMvc.perform(get("/accounts/99999"))
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
            List<AccountResponseDto> accounts = List.of(
                    responseFor("11111", "Ana Silva", AccountStatus.ACTIVE),
                    responseFor("22222", "Bruno Costa", AccountStatus.BLOCKED));
            when(accountService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(accounts, pageable, 5));

            mockMvc.perform(get("/accounts").param("page", "0").param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.content[0].accountNumber").value("11111"))
                    .andExpect(jsonPath("$.content[1].status").value("BLOCKED"))
                    .andExpect(jsonPath("$.page.size").value(2))
                    .andExpect(jsonPath("$.page.number").value(0))
                    .andExpect(jsonPath("$.page.totalElements").value(5))
                    .andExpect(jsonPath("$.page.totalPages").value(3));
        }

        @Test
        void shouldPassPaginationParametersToService() throws Exception {
            when(accountService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            mockMvc.perform(get("/accounts").param("page", "2").param("size", "5"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(accountService).findAll(captor.capture());
            assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
            assertThat(captor.getValue().getPageSize()).isEqualTo(5);
        }
    }

    @Nested
    class Update {

        @Test
        void shouldReturnUpdatedAccount() throws Exception {
            UpdateAccountRequestDto request = new UpdateAccountRequestDto("Ana Souza", null, null);
            when(accountService.update("12345", request)).thenReturn(responseFor("12345", "Ana Souza", AccountStatus.ACTIVE));

            mockMvc.perform(patch("/accounts/12345")
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

            mockMvc.perform(patch("/accounts/12345")
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
            mockMvc.perform(patch("/accounts/12345")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"holderName\": \"" + holderName + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.holderName").exists());

            verify(accountService, never()).update(anyString(), any());
        }

        @Test
        void shouldReturnBadRequestWhenEmailIsInvalid() throws Exception {
            mockMvc.perform(patch("/accounts/12345")
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
            mockMvc.perform(patch("/accounts/12345")
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

            mockMvc.perform(patch("/accounts/99999")
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

            mockMvc.perform(patch("/accounts/12345")
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
            mockMvc.perform(delete("/accounts/12345"))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));

            verify(accountService).close("12345");
        }

        @Test
        void shouldReturnNotFoundWhenAccountDoesNotExist() throws Exception {
            doThrow(new AccountNotFoundException("99999")).when(accountService).close("99999");

            mockMvc.perform(delete("/accounts/99999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Account 99999 not found"));
        }

        @Test
        void shouldReturnUnprocessableContentWhenBalanceIsNotZero() throws Exception {
            doThrow(new BusinessException("Account balance must be zero to close"))
                    .when(accountService).close("12345");

            mockMvc.perform(delete("/accounts/12345"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail").value("Account balance must be zero to close"));
        }
    }

    @Nested
    class ExceptionHandling {

        @Test
        void shouldReturnUnprocessableContentForBusinessException() throws Exception {
            when(accountService.findByAccountNumber("12345")).thenThrow(new BusinessException("Insufficient balance"));

            mockMvc.perform(get("/accounts/12345"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail").value("Insufficient balance"));
        }

        @Test
        void shouldHideInternalDetailsForUnexpectedErrors() throws Exception {
            when(accountService.findByAccountNumber("12345"))
                    .thenThrow(new RuntimeException("SELECT * FROM accounts -- connection refused"));

            mockMvc.perform(get("/accounts/12345"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                    .andExpect(content().string(not(containsString("SELECT"))));
        }
    }
}
