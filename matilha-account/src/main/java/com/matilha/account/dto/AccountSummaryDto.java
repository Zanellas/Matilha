package com.matilha.account.dto;

import com.matilha.account.entity.AccountStatus;

public record AccountSummaryDto(
    String accountNumber,
    String holderName,
    AccountStatus status
) {
}
