package com.matilha.transfer.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreateTransferRequestDto(
                @NotBlank @Pattern(regexp = "\\d{5}") String sourceAccountNumber,
                @NotBlank @Pattern(regexp = "\\d{5}") String targetAccountNumber,
                @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount) {

}
