package com.matilha.account.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.matilha.account.dto.InternalTransferRequestDto;
import com.matilha.account.dto.InternalTransferResponseDto;
import com.matilha.account.service.InternalTransferService;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/transfers")
public class InternalTransferController {

    private final InternalTransferService transferService;

    @PostMapping
    public ResponseEntity<InternalTransferResponseDto> transfer(@RequestBody @Valid InternalTransferRequestDto request) {
        return ResponseEntity.ok(transferService.transfer(request));
    }
}
