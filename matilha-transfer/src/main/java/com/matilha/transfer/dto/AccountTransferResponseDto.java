package com.matilha.transfer.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountTransferResponseDto(
        UUID transferId,
        String sourceAccountNumber,
        BigDecimal sourceBalanceAfter,
        String targetAccountNumber,
        BigDecimal targetBalanceAfter,
        BigDecimal amount) {

}
