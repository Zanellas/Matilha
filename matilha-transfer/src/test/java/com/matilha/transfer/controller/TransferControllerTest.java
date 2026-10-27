package com.matilha.transfer.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.matilha.transfer.dto.CreateTransferRequestDto;
import com.matilha.transfer.dto.TransferResponseDto;
import com.matilha.transfer.entity.TransferStatus;
import com.matilha.transfer.exception.BusinessException;
import com.matilha.transfer.exception.TransferNotFoundException;
import com.matilha.transfer.service.TransferService;

@WebMvcTest(TransferController.class)
class TransferControllerTest {

    private static final UUID KEY = UUID.fromString("3f1c2a9e-7b4d-4c1a-9e2f-5a6b7c8d9e01");
    private static final UUID TRANSFER_ID = UUID.fromString("8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d");

    private static final String VALID_REQUEST = """
            {
              "sourceAccountNumber": "10001",
              "targetAccountNumber": "10002",
              "amount": 250.50
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    private static TransferResponseDto response(TransferStatus status, String failureReason) {
        return new TransferResponseDto(TRANSFER_ID, status, "10001", "10002", new BigDecimal("250.50"),
                failureReason, Instant.parse("2026-10-26T12:00:00Z"));
    }

    private static String requestWith(String field, String jsonValue) {
        return VALID_REQUEST.replaceFirst("\"" + field + "\": [^,\\n]*", "\"" + field + "\": " + jsonValue);
    }

    private static MockHttpServletRequestBuilder postTransfer(String body) {
        return post("/transfers")
                .header("Idempotency-Key", KEY.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Nested
    class Create {

        @Test
        void shouldReturnCreatedWhenCompleted() throws Exception {
            when(transferService.create(eq(KEY), any())).thenReturn(response(TransferStatus.COMPLETED, null));

            mockMvc.perform(postTransfer(VALID_REQUEST))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/transfers/" + TRANSFER_ID))
                    .andExpect(jsonPath("$.id").value(TRANSFER_ID.toString()))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.sourceAccountNumber").value("10001"))
                    .andExpect(jsonPath("$.targetAccountNumber").value("10002"))
                    .andExpect(jsonPath("$.amount").value(250.50))
                    .andExpect(jsonPath("$.createdAt").exists());
        }

        @Test
        void shouldReturnAcceptedWhenPending() throws Exception {
            when(transferService.create(eq(KEY), any())).thenReturn(response(TransferStatus.PENDING, null));

            mockMvc.perform(postTransfer(VALID_REQUEST))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Location", "/transfers/" + TRANSFER_ID))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        void shouldReturnProblemDetailWithTransferIdWhenFailed() throws Exception {
            when(transferService.create(eq(KEY), any()))
                    .thenReturn(response(TransferStatus.FAILED, "Insufficient balance"));

            mockMvc.perform(postTransfer(VALID_REQUEST))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(header().string("Content-Type", "application/problem+json"))
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.title").value("Transfer failed"))
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.detail").value("Insufficient balance"))
                    .andExpect(jsonPath("$.transferId").value(TRANSFER_ID.toString()))
                    .andExpect(jsonPath("$.instance").value("/transfers"));
        }

        @Test
        void shouldReturnSameProblemDetailWhenReplayingFailedTransfer() throws Exception {
            when(transferService.create(eq(KEY), any()))
                    .thenReturn(response(TransferStatus.FAILED, "Insufficient balance"));

            for (int attempt = 0; attempt < 2; attempt++) {
                mockMvc.perform(postTransfer(VALID_REQUEST))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.detail").value("Insufficient balance"))
                        .andExpect(jsonPath("$.transferId").value(TRANSFER_ID.toString()));
            }
        }

        @Test
        void shouldUseSameFormatForFailedTransferAndOtherBusinessErrors() throws Exception {
            when(transferService.create(eq(KEY), any()))
                    .thenThrow(new BusinessException("Idempotency key already used with different data"));

            mockMvc.perform(postTransfer(VALID_REQUEST))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(header().string("Content-Type", "application/problem+json"))
                    .andExpect(jsonPath("$.detail").exists())
                    .andExpect(jsonPath("$.transferId").doesNotExist());
        }

        @Test
        void shouldPassKeyAndBodyToService() throws Exception {
            when(transferService.create(any(), any())).thenReturn(response(TransferStatus.COMPLETED, null));

            mockMvc.perform(postTransfer(VALID_REQUEST));

            verify(transferService).create(KEY,
                    new CreateTransferRequestDto("10001", "10002", new BigDecimal("250.50")));
        }

        @Test
        void shouldAcceptHeaderNameInAnyCase() throws Exception {
            when(transferService.create(eq(KEY), any())).thenReturn(response(TransferStatus.COMPLETED, null));

            mockMvc.perform(post("/transfers")
                    .header("idempotency-key", KEY.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
                    .andExpect(status().isCreated());
        }

        @Test
        void shouldReturnUnprocessableWhenKeyWasUsedWithDifferentData() throws Exception {
            when(transferService.create(eq(KEY), any()))
                    .thenThrow(new BusinessException("Idempotency key already used with different data"));

            mockMvc.perform(postTransfer(VALID_REQUEST))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail").value("Idempotency key already used with different data"));
        }

        @Test
        void shouldReturnConflictOnDataIntegrityViolation() throws Exception {
            when(transferService.create(eq(KEY), any())).thenThrow(new DataIntegrityViolationException("boom"));

            mockMvc.perform(postTransfer(VALID_REQUEST))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("The request conflicts with existing data"));
        }

        @Test
        void shouldHideUnexpectedErrorDetails() throws Exception {
            when(transferService.create(eq(KEY), any())).thenThrow(new IllegalStateException("secret internals"));

            mockMvc.perform(postTransfer(VALID_REQUEST))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
        }
    }

    @Nested
    class IdempotencyKeyHeader {

        @Test
        void shouldRejectMissingHeader() throws Exception {
            mockMvc.perform(post("/transfers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
                    .andExpect(status().isBadRequest());

            verify(transferService, never()).create(any(), any());
        }

        @ParameterizedTest
        @ValueSource(strings = { "abc", "123", "3f1c2a9e-7b4d" })
        void shouldRejectHeaderThatIsNotUuid(String key) throws Exception {
            mockMvc.perform(post("/transfers")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST))
                    .andExpect(status().isBadRequest());

            verify(transferService, never()).create(any(), any());
        }
    }

    @Nested
    class Validation {

        @ParameterizedTest
        @ValueSource(strings = { "null", "\"\"", "\"1234\"", "\"123456\"", "\"abcde\"" })
        void shouldRejectInvalidSourceAccountNumber(String value) throws Exception {
            mockMvc.perform(postTransfer(requestWith("sourceAccountNumber", value)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.sourceAccountNumber").exists());

            verify(transferService, never()).create(any(), any());
        }

        @ParameterizedTest
        @ValueSource(strings = { "null", "\"\"", "\"1234\"", "\"abcde\"" })
        void shouldRejectInvalidTargetAccountNumber(String value) throws Exception {
            mockMvc.perform(postTransfer(requestWith("targetAccountNumber", value)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.targetAccountNumber").exists());
        }

        @ParameterizedTest
        @ValueSource(strings = { "null", "0", "-10.00", "10.555" })
        void shouldRejectInvalidAmount(String value) throws Exception {
            mockMvc.perform(postTransfer(requestWith("amount", value)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.amount").exists());

            verify(transferService, never()).create(any(), any());
        }

        @Test
        void shouldRejectMalformedJson() throws Exception {
            mockMvc.perform(postTransfer("{ not json"))
                    .andExpect(status().isBadRequest());

            verify(transferService, never()).create(any(), any());
        }
    }

    @Nested
    class FindById {

        @Test
        void shouldReturnTransfer() throws Exception {
            when(transferService.findById(TRANSFER_ID)).thenReturn(response(TransferStatus.PENDING, null));

            mockMvc.perform(get("/transfers/{id}", TRANSFER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(TRANSFER_ID.toString()))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        void shouldReturnNotFound() throws Exception {
            when(transferService.findById(TRANSFER_ID)).thenThrow(new TransferNotFoundException(TRANSFER_ID));

            mockMvc.perform(get("/transfers/{id}", TRANSFER_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Transfer not found: " + TRANSFER_ID));
        }

        @Test
        void shouldRejectIdThatIsNotUuid() throws Exception {
            mockMvc.perform(get("/transfers/{id}", "abc"))
                    .andExpect(status().isBadRequest());

            verify(transferService, never()).findById(any());
        }
    }
}
