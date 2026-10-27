package com.matilha.transfer.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.matilha.transfer.dto.CreateTransferRequestDto;
import com.matilha.transfer.dto.TransferResponseDto;
import com.matilha.transfer.entity.TransferStatus;
import com.matilha.transfer.exception.TransferFailedException;
import com.matilha.transfer.service.TransferService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    public ResponseEntity<TransferResponseDto> create(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody @Valid CreateTransferRequestDto request) {
        TransferResponseDto response = transferService.create(idempotencyKey, request);
        if (response.status() == TransferStatus.FAILED) {
            throw new TransferFailedException(response);
        }
        return ResponseEntity.status(statusFor(response.status()))
                .location(URI.create("/transfers/" + response.id()))
                .body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransferResponseDto> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(transferService.findById(id));
    }

    private static HttpStatus statusFor(TransferStatus status) {
        return switch (status) {
            case COMPLETED -> HttpStatus.CREATED;
            case PENDING -> HttpStatus.ACCEPTED;
            case FAILED -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
    }
}
