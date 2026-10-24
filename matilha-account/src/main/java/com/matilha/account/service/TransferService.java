package com.matilha.account.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.matilha.account.dto.TransferRequestDto;
import com.matilha.account.dto.TransferResponseDto;
import com.matilha.account.entity.Account;
import com.matilha.account.entity.AccountMovement;
import com.matilha.account.entity.MovementOperation;
import com.matilha.account.entity.MovementType;
import com.matilha.account.exception.AccountNotFoundException;
import com.matilha.account.exception.BusinessException;
import com.matilha.account.repository.AccountMovementRepository;
import com.matilha.account.repository.AccountRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final AccountMovementRepository accountMovementRepository;

    @Transactional
    public TransferResponseDto transfer(TransferRequestDto request) {
        List<AccountMovement> existing = accountMovementRepository.findByOperationIdAndOperation(request.transferId(), MovementOperation.TRANSFER);
        if (!existing.isEmpty()) {
            TransferResponseDto previous = toResponse(request.transferId(), existing);
            if (!isSameTransfer(previous, request)) {
                throw new BusinessException("Transfer already processed with different data");
            }
            return previous;
        }

        String sourceNumber = request.sourceAccountNumber();
        String targetNumber = request.targetAccountNumber();
        if (sourceNumber.equals(targetNumber)) {
            throw new BusinessException("Source and target accounts must be different");
        }

        Account source;
        Account target;
        if (sourceNumber.compareTo(targetNumber) < 0) {
            source = lockAccount(sourceNumber);
            target = lockAccount(targetNumber);
        } else {
            target = lockAccount(targetNumber);
            source = lockAccount(sourceNumber);
        }

        source.withdraw(request.amount());
        target.deposit(request.amount());

        AccountMovement debit = AccountMovement.transferDebit(source, request.transferId(), request.amount());
        AccountMovement credit = AccountMovement.transferCredit(target, request.transferId(), request.amount());
        accountMovementRepository.saveAll(List.of(debit, credit));

        return toResponse(request.transferId(), List.of(debit, credit));
    }

    private boolean isSameTransfer(TransferResponseDto previous, TransferRequestDto request) {
        return previous.sourceAccountNumber().equals(request.sourceAccountNumber())
                && previous.targetAccountNumber().equals(request.targetAccountNumber())
                && previous.amount().compareTo(request.amount()) == 0;
    }

    private Account lockAccount(String accountNumber) {
        return accountRepository.findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }

    private TransferResponseDto toResponse(UUID transferId, List<AccountMovement> movements) {
        AccountMovement debit = findByType(movements, MovementType.DEBIT);
        AccountMovement credit = findByType(movements, MovementType.CREDIT);
        return new TransferResponseDto(
                transferId,
                debit.getAccount().getAccountNumber(), debit.getBalanceAfter(),
                credit.getAccount().getAccountNumber(), credit.getBalanceAfter(),
                debit.getAmount());
    }

    private AccountMovement findByType(List<AccountMovement> movements, MovementType type) {
        return movements.stream()
                .filter(movement -> movement.getType() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Transfer without " + type + " movement"));
    }
}
