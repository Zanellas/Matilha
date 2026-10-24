package com.matilha.account.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.matilha.account.dto.DepositRequestDto;
import com.matilha.account.dto.DepositResponseDto;
import com.matilha.account.entity.Account;
import com.matilha.account.entity.AccountMovement;
import com.matilha.account.entity.MovementOperation;
import com.matilha.account.exception.AccountNotFoundException;
import com.matilha.account.repository.AccountMovementRepository;
import com.matilha.account.repository.AccountRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DepositService {

    private final AccountRepository accountRepository;
    private final AccountMovementRepository accountMovementRepository;

    @Transactional
    public DepositResponseDto deposit(DepositRequestDto request) {
        List<AccountMovement> existing = accountMovementRepository
                .findByOperationIdAndOperation(request.depositId(), MovementOperation.DEPOSIT);
        if (!existing.isEmpty()) {
            return toResponse(existing.getFirst());
        }

        Account account = accountRepository.findByAccountNumberForUpdate(request.accountNumber())
                .orElseThrow(() -> new AccountNotFoundException(request.accountNumber()));

        account.deposit(request.amount());

        AccountMovement movement = AccountMovement.deposit(account, request.depositId(), request.amount());
        accountMovementRepository.save(movement);
        return toResponse(movement);
    }

    private DepositResponseDto toResponse(AccountMovement movement) {
        return new DepositResponseDto(
                movement.getOperationId(),
                movement.getAccount().getAccountNumber(),
                movement.getAmount(),
                movement.getBalanceAfter());
    }

}
