package com.matilha.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import com.matilha.account.entity.Status;
import com.matilha.account.exception.AccountNotFoundException;
import com.matilha.account.exception.BusinessException;
import com.matilha.account.service.AccountService;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    @Nested
    class Create {

        @Test
        void shouldReturnCreatedWithAccount() throws Exception {
            CreateAccountRequestDto request = new CreateAccountRequestDto("Ana Silva", "12345678900");
            when(accountService.create(request))
                    .thenReturn(new AccountResponseDto("12345", "Ana Silva", Status.ACTIVE));

            mockMvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"holderName": "Ana Silva", "holderDocument": "12345678900"}
                            """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accountNumber").value("12345"))
                    .andExpect(jsonPath("$.holderName").value("Ana Silva"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.holderDocument").doesNotExist());
        }

        @Test
        void shouldReturnBadRequestWithFieldErrorsWhenFieldsAreBlank() throws Exception {
            mockMvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"holderName": "", "holderDocument": " "}
                            """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail").value("Invalid request"))
                    .andExpect(jsonPath("$.errors.holderName").exists())
                    .andExpect(jsonPath("$.errors.holderDocument").exists());

            verify(accountService, never()).create(any());
        }

        @Test
        void shouldReturnBadRequestWhenFieldsAreMissing() throws Exception {
            mockMvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.holderName").exists())
                    .andExpect(jsonPath("$.errors.holderDocument").exists());

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
            when(accountService.findByAccountNumber("12345"))
                    .thenReturn(new AccountResponseDto("12345", "Ana Silva", Status.ACTIVE));

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
                    new AccountResponseDto("11111", "Ana Silva", Status.ACTIVE),
                    new AccountResponseDto("22222", "Bruno Costa", Status.BLOCKED));
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
