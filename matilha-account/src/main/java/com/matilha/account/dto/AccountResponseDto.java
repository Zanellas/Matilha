package com.matilha.account.dto;

import com.matilha.account.entity.Status;

public record AccountResponseDto(
    String accountNumber, 
    String holderName,
    Status status
) {

}