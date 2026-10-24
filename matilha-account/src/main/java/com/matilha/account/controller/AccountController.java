package com.matilha.account.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.matilha.account.dto.AccountResponseDto;
import com.matilha.account.dto.AccountSummaryDto;
import com.matilha.account.dto.CreateAccountRequestDto;
import com.matilha.account.dto.UpdateAccountRequestDto;
import com.matilha.account.service.AccountService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponseDto> create(@RequestBody @Valid CreateAccountRequestDto request) {
        AccountResponseDto response = accountService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponseDto> findByAccountNumber(@PathVariable String accountNumber) {
        return ResponseEntity.ok(accountService.findByAccountNumber(accountNumber));
    }

    @GetMapping
    public ResponseEntity<PagedModel<AccountSummaryDto>> findAll(Pageable pageable) {
        return ResponseEntity.ok(new PagedModel<>(accountService.findAll(pageable)));
    }

    @PatchMapping("/{accountNumber}")
    public ResponseEntity<AccountResponseDto> update(@PathVariable String accountNumber,
            @RequestBody @Valid UpdateAccountRequestDto request) {
        return ResponseEntity.ok(accountService.update(accountNumber, request));
    }

    @DeleteMapping("/{accountNumber}")
    public ResponseEntity<Void> close(@PathVariable String accountNumber) {
        accountService.close(accountNumber);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{accountNummber}/block")
    public ResponseEntity<AccountResponseDto> block(@PathVariable String accountNumber) {
        return ResponseEntity.ok(accountService.block(accountNumber));
    }

    @PostMapping("/{accountNummber}/unblock")
    public ResponseEntity<AccountResponseDto> unblock(@PathVariable String accountNumber) {
        return ResponseEntity.ok(accountService.block(accountNumber));
    }

}
