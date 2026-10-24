package com.matilha.account.dto;

import com.matilha.account.entity.Status;

public record AccountSummaryDto(
    String accountNumber,
    String holderName,
    Status staus
) {
}
