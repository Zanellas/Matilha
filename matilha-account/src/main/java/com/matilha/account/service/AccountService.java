package com.matilha.account.service;

import java.security.SecureRandom;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.matilha.account.dto.AccountResponseDto;
import com.matilha.account.dto.AccountSummaryDto;
import com.matilha.account.dto.CreateAccountRequestDto;
import com.matilha.account.dto.UpdateAccountRequestDto;
import com.matilha.account.entity.Account;
import com.matilha.account.exception.AccountNotFoundException;
import com.matilha.account.repository.AccountRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public AccountResponseDto create(CreateAccountRequestDto request) {
        Account account = new Account(generateUniqueAccountNumber(), request.holderName(), request.holderDocument(),
                request.holderEmail(), request.holderPhone());
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
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
        return toResponse(account);
    }

    @Transactional(readOnly = true)
    public Page<AccountSummaryDto> findAll(Pageable pageable) {
        return accountRepository.findAll(pageable).map(this::toSummary);
    }

    private AccountResponseDto toResponse(Account account) {
        return new AccountResponseDto(account.getAccountNumber(), account.getHolderName(), account.getHolderPhone(),
                account.getHolderEmail(), account.getStatus(),
                account.getBalance());
    }

    private AccountSummaryDto toSummary(Account account) {
        return new AccountSummaryDto(account.getAccountNumber(), account.getHolderName(), account.getStatus());
    }

    @Transactional
    public AccountResponseDto update(String accountNumber, UpdateAccountRequestDto request) {
        Account account = findAccountOrThrow(accountNumber);
        account.updateHolderData(request.holderName(), request.holderEmail(), request.holderPhone());
        return toResponse(account);
    }

    @Transactional
    public void close(String accountNumber) {
        findAccountOrThrow(accountNumber).close();
    }

    private Account findAccountOrThrow(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }

    @Transactional
    public AccountResponseDto block(String accountNumber) {
        Account account = findAccountOrThrow(accountNumber);
        account.block();
        return toResponse(account);
    }

    @Transactional
    public AccountResponseDto unBlock(String accountNumber) {
        Account account = findAccountOrThrow(accountNumber);
        account.unblock();
        return toResponse(account);
    }
}
