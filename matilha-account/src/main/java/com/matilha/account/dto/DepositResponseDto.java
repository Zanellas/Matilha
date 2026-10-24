package com.matilha.account.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DepositResponseDto(
    UUID depositId,
    String accountNumber,
    BigDecimal amount,
    BigDecimal balanceAfter
) {
    
}
