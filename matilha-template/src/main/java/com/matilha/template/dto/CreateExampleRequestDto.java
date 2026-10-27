package com.matilha.template.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateExampleRequestDto(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 255) String description) {

}
