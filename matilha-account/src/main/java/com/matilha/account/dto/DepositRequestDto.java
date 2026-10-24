package com.matilha.account.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record DepositRequestDto(
        @NotNull UUID depositId,
        @NotBlank @Pattern(regexp = "\\d{5}") String accountNumber,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount) {

}
