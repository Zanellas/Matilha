package com.matilha.account.dto;

import java.math.BigDecimal;

import com.matilha.account.entity.AccountStatus;

public record AccountResponseDto(
        String accountNumber,
        String holderName,
        String holderPhone,
        String holderEmail,
        AccountStatus status, 
        BigDecimal balance) {

}