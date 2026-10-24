package com.matilha.account.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.matilha.account.dto.DepositRequestDto;
import com.matilha.account.dto.DepositResponseDto;
import com.matilha.account.service.DepositService;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/deposits")
public class InternalDepositController {

    private final DepositService depositService;

    @PostMapping
    public ResponseEntity<DepositResponseDto> deposit(@RequestBody @Valid DepositRequestDto request) {
        return ResponseEntity.ok(depositService.deposit(request));
    }

}
