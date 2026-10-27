package com.matilha.template.dto;

import java.time.Instant;
import java.util.UUID;

import com.matilha.template.entity.Example;

public record ExampleResponseDto(
        UUID id,
        String name,
        String description,
        Instant createdAt) {

    public static ExampleResponseDto from(Example example) {
        return new ExampleResponseDto(
                example.getId(),
                example.getName(),
                example.getDescription(),
                example.getCreatedAt());
    }
}
