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

import com.matilha.account.dto.TransferRequestDto;
import com.matilha.account.dto.TransferResponseDto;
import com.matilha.account.exception.AccountNotFoundException;
import com.matilha.account.exception.BusinessException;
import com.matilha.account.service.TransferService;

@WebMvcTest(InternalTransferController.class)
class InternalTransferControllerTest {

    private static final UUID TRANSFER_ID = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");

    private static final String VALID_REQUEST = """
            {
              "transferId": "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
              "sourceAccountNumber": "12345",
              "targetAccountNumber": "67890",
              "amount": 150.00
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    private static String requestWith(String field, String jsonValue) {
        return VALID_REQUEST.replaceFirst("\"" + field + "\": [^,\\n]*", "\"" + field + "\": " + jsonValue);
    }

    @Nested
    class Transfer {

        @Test
        void shouldReturnOkWithResultingBalances() throws Exception {
            when(transferService.transfer(any())).thenReturn(new TransferResponseDto(TRANSFER_ID,
                    "12345", new BigDecimal("350.00"), "67890", new BigDecimal("250.00"), new BigDecimal("150.00")));

            mockMvc.perform(post("/internal/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transferId").value(TRANSFER_ID.toString()))
                    .andExpect(jsonPath("$.sourceAccountNumber").value("12345"))
                    .andExpect(jsonPath("$.sourceBalanceAfter").value(350.00))
                    .andExpect(jsonPath("$.targetAccountNumber").value("67890"))
                    .andExpect(jsonPath("$.targetBalanceAfter").value(250.00))
                    .andExpect(jsonPath("$.amount").value(150.00));
        }

        @Test
        void shouldPassRequestBodyToService() throws Exception {
            when(transferService.transfer(any())).thenReturn(new TransferResponseDto(TRANSFER_ID,
                    "12345", BigDecimal.ZERO, "67890", BigDecimal.ZERO, new BigDecimal("150.00")));

            mockMvc.perform(post("/internal/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
                    .andExpect(status().isOk());

            verify(transferService).transfer(
                    new TransferRequestDto(TRANSFER_ID, "12345", "67890", new BigDecimal("150.00")));
        }
    }

    @Nested
    class RequestValidation {

        @Test
        void shouldReturnBadRequestWhenFieldsAreMissing() throws Exception {
            mockMvc.perform(post("/internal/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.transferId").exists())
                    .andExpect(jsonPath("$.errors.sourceAccountNumber").exists())
                    .andExpect(jsonPath("$.errors.targetAccountNumber").exists())
                    .andExpect(jsonPath("$.errors.amount").exists());

            verify(transferService, never()).transfer(any());
        }

        @ParameterizedTest
        @ValueSource(strings = { "\"1234\"", "\"123456\"", "\"ABCDE\"", "\"\"" })
        void shouldReturnBadRequestWhenSourceAccountNumberIsInvalid(String accountNumber) throws Exception {
            mockMvc.perform(post("/internal/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("sourceAccountNumber", accountNumber)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.sourceAccountNumber").exists());

            verify(transferService, never()).transfer(any());
        }

        @ParameterizedTest
        @ValueSource(strings = { "\"1234\"", "\"ABCDE\"" })
        void shouldReturnBadRequestWhenTargetAccountNumberIsInvalid(String accountNumber) throws Exception {
            mockMvc.perform(post("/internal/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("targetAccountNumber", accountNumber)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.targetAccountNumber").exists());

            verify(transferService, never()).transfer(any());
        }

        @ParameterizedTest
        @ValueSource(strings = { "0", "-10.00", "10.555" })
        void shouldReturnBadRequestWhenAmountIsInvalid(String amount) throws Exception {
            mockMvc.perform(post("/internal/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("amount", amount)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.amount").exists());

            verify(transferService, never()).transfer(any());
        }

        @Test
        void shouldReturnBadRequestWhenTransferIdIsNotUuid() throws Exception {
            mockMvc.perform(post("/internal/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestWith("transferId", "\"not-a-uuid\"")))
                    .andExpect(status().isBadRequest());

            verify(transferService, never()).transfer(any());
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void shouldReturnUnprocessableContentForBusinessRuleViolation() throws Exception {
            when(transferService.transfer(any())).thenThrow(new BusinessException("Insufficient balance"));

            mockMvc.perform(post("/internal/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail").value("Insufficient balance"));
        }

        @Test
        void shouldReturnNotFoundWhenAccountDoesNotExist() throws Exception {
            when(transferService.transfer(any())).thenThrow(new AccountNotFoundException("67890"));

            mockMvc.perform(post("/internal/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Account 67890 not found"));
        }

        @Test
        void shouldReturnConflictWhenSameTransferIsAppliedConcurrently() throws Exception {
            when(transferService.transfer(any()))
                    .thenThrow(new DataIntegrityViolationException("uk_account_movements_transfer_type"));

            mockMvc.perform(post("/internal/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("The request conflicts with existing data"));
        }
    }
}
