package com.matilha.account.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.matilha.account.dto.DepositRequestDto;
import com.matilha.account.dto.DepositResponseDto;
import com.matilha.account.exception.AccountNotFoundException;
import com.matilha.account.exception.BusinessException;
import com.matilha.account.service.DepositService;

@WebMvcTest(InternalDepositController.class)
class InternalDepositControllerTest {

    private static final UUID DEPOSIT_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    private static final String VALID_REQUEST = """
            {
              "depositId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
              "accountNumber": "12345",
              "amount": 50.25
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DepositService depositService;

    private static String requestWith(String field, String jsonValue) {
        return VALID_REQUEST.replaceFirst("\"" + field + "\": [^,\\n]*", "\"" + field + "\": " + jsonValue);
    }

    @Nested
    class Deposit {

        @Test
        void shouldReturnOkWithResultingBalance() throws Exception {
            when(depositService.deposit(any())).thenReturn(new DepositResponseDto(
                    DEPOSIT_ID, "12345", new BigDecimal("50.25"), new BigDecimal("150.25")));

            mockMvc.perform(post("/internal/deposits")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.depositId").value(DEPOSIT_ID.toString()))
                    .andExpect(jsonPath("$.accountNumber").value("12345"))
                    .andExpect(jsonPath("$.amount").value(50.25))
                    .andExpect(jsonPath("$.balanceAfter").value(150.25));
        }

        @Test
        void shouldPassRequestBodyToService() throws Exception {
            when(depositService.deposit(any())).thenReturn(new DepositResponseDto(
                    DEPOSIT_ID, "12345", new BigDecimal("50.25"), new BigDecimal("150.25")));

            mockMvc.perform(post("/internal/deposits")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
                    .andExpect(status().isOk());

            verify(depositService).deposit(new DepositRequestDto(DEPOSIT_ID, "12345", new BigDecimal("50.25")));
        }
    }

    @Nested
    class RequestValidation {

        @Test
        void shouldReturnBadRequestWhenFieldsAreMissing() throws Exception {
            mockMvc.perform(post("/internal/deposits")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.depositId").exists())
                    .andExpect(jsonPath("$.errors.accountNumber").exists())
                    .andExpect(jsonPath("$.errors.amount").exists());

            verify(depositService, never()).deposit(any());
        }

        @ParameterizedTest
        @ValueSource(strings = { "\"1234\"", "\"123456\"", "\"ABCDE\"", "\"\"" })
        void shouldReturnBadRequestWhenAccountNumberIsInvalid(String accountNumber) throws Exception {
            mockMvc.perform(post("/internal/deposits")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("accountNumber", accountNumber)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.accountNumber").exists());

            verify(depositService, never()).deposit(any());
        }

        @ParameterizedTest
        @ValueSource(strings = { "0", "-10.00", "10.555" })
        void shouldReturnBadRequestWhenAmountIsInvalid(String amount) throws Exception {
            mockMvc.perform(post("/internal/deposits")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("amount", amount)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.amount").exists());

            verify(depositService, never()).deposit(any());
        }

        @Test
        void shouldReturnBadRequestWhenDepositIdIsNotUuid() throws Exception {
            mockMvc.perform(post("/internal/deposits")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("depositId", "\"not-a-uuid\"")))
                    .andExpect(status().isBadRequest());

            verify(depositService, never()).deposit(any());
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void shouldReturnUnprocessableContentWhenAccountIsNotActive() throws Exception {
            when(depositService.deposit(any())).thenThrow(new BusinessException("Account is not active"));

            mockMvc.perform(post("/internal/deposits")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail").value("Account is not active"));
        }

        @Test
        void shouldReturnNotFoundWhenAccountDoesNotExist() throws Exception {
            when(depositService.deposit(any())).thenThrow(new AccountNotFoundException("12345"));

            mockMvc.perform(post("/internal/deposits")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Account 12345 not found"));
        }

        @Test
        void shouldReturnConflictWhenSameDepositIsAppliedConcurrently() throws Exception {
            when(depositService.deposit(any()))
                    .thenThrow(new DataIntegrityViolationException("uk_account_movements_operation_type"));

            mockMvc.perform(post("/internal/deposits")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
                    .andExpect(status().isConflict());
        }
    }
}
