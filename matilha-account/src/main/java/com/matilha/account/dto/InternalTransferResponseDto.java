package com.matilha.account.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record InternalTransferResponseDto(
        UUID transferId,
        String sourceAccountNumber,
        BigDecimal sourceBalanceAfter,
        String targetAccountNumber,
        BigDecimal targetBalanceAfter,
        BigDecimal amount) {
}
