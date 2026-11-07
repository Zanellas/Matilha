package com.matilha.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;

public record UpdateAccountRequestDto(
        @Pattern(regexp = ".*\\S.*") String holderName,
        @Email String holderEmail,
        @Pattern(regexp = "\\(?\\d{2}\\)?\\s?\\d{4,5}-?\\d{4}") String holderPhone) {

}
