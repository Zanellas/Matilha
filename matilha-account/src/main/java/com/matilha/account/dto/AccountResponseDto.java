package com.matilha.account.dto;

import java.math.BigDecimal;

import com.matilha.account.entity.Status;

public record AccountResponseDto(
        String accountNumber,
        String holderName,
        String holderPhone,
        String holderEmail,
        Status status, 
        BigDecimal balance) {

}