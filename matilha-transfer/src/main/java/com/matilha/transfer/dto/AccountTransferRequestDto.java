package com.matilha.transfer.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountTransferRequestDto(
        UUID transferId,
        String sourceAccountNumber,
        String targetAccountNumber,
        BigDecimal amount) {

}
