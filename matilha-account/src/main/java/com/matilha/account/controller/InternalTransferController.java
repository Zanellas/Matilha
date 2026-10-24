package com.matilha.account.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.matilha.account.dto.TransferRequestDto;
import com.matilha.account.dto.TransferResponseDto;
import com.matilha.account.service.TransferService;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/transfers")
public class InternalTransferController {

    private final TransferService transferService;

    @PostMapping
    public ResponseEntity<TransferResponseDto> transfer(@RequestBody @Valid TransferRequestDto request) {
        return ResponseEntity.ok(transferService.transfer(request));
    }
}
