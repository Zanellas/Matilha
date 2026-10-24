package com.matilha.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequestDto(
        @NotBlank String holderName,
        @NotBlank String holderDocument,
        @NotBlank @Email String holderEmail,
        @NotBlank @Pattern(regexp = "\\(?\\d{2}\\)?\\s?\\d{4,5}-?\\d{4}") String holderPhone) {

}
