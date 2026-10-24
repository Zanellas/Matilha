package com.matilha.account.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAccountRequestDto(
        @NotBlank String holderName,
        @NotBlank String holderDocument) {

}
