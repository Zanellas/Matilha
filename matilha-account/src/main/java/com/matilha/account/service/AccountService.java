package com.matilha.account.service;

import java.security.SecureRandom;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.matilha.account.dto.AccountResponseDto;
import com.matilha.account.dto.CreateAccountRequestDto;
import com.matilha.account.entity.Account;
import com.matilha.account.repository.AccountRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public AccountResponseDto create(CreateAccountRequestDto request) {
        Account account = new Account(generateUniqueAccountNumber(), request.holderName(), request.holderDocument());
        Account saved = accountRepository.save(account);
        return toResponse(saved);
    }

    private String generateUniqueAccountNumber() {
        String number;
        do {
            number = Long.toString(RANDOM.nextLong(10_000L, 100_000L));
        } while (accountRepository.existsByAccountNumber(number));
        return number;
    }

    @Transactional(readOnly = true)
    public AccountResponseDto findByAccountNumber(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        return toResponse(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponseDto> findAll() {
        return accountRepository.findAll().stream().map(this::toResponse).toList();
    }

    private AccountResponseDto toResponse(Account account) {
        return new AccountResponseDto(account.getAccountNumber(), account.getHolderName(), account.getStatus());
    }
}
