package com.matilha.transfer.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.matilha.transfer.client.AccountClient;
import com.matilha.transfer.dto.AccountTransferRequestDto;
import com.matilha.transfer.dto.CreateTransferRequestDto;
import com.matilha.transfer.dto.TransferResponseDto;
import com.matilha.transfer.entity.Transfer;
import com.matilha.transfer.exception.AccountTransferRejectedException;
import com.matilha.transfer.exception.AccountUnavailableException;
import com.matilha.transfer.exception.BusinessException;
import com.matilha.transfer.exception.TransferNotFoundException;
import com.matilha.transfer.repository.TransferRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final TransferRepository transferRepository;
    private final AccountClient accountClient;

    public TransferResponseDto create(UUID idempotencyKey, CreateTransferRequestDto request) {
        Optional<Transfer> existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        Transfer transfer;
        try {
            transfer = transferRepository.save(new Transfer(idempotencyKey, request.sourceAccountNumber(),
                    request.targetAccountNumber(), request.amount()));
        } catch (DataIntegrityViolationException ex) {
            Transfer winner = transferRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> ex);
            return replay(winner, request);
        }

        AccountTransferRequestDto accountRequest = new AccountTransferRequestDto(
                transfer.getId(),
                transfer.getSourceAccountNumber(),
                transfer.getTargetAccountNumber(),
                transfer.getAmount());

        try {
            accountClient.transfer(accountRequest);
            transfer.complete();
        } catch (AccountTransferRejectedException ex) {
            transfer.fail(ex.getMessage());
        } catch (AccountUnavailableException ex) {
            log.warn("Account service unavailable, transfer {} stays PENDING", transfer.getId(), ex);
        }

        return TransferResponseDto.from(transferRepository.save(transfer));
    }

    private TransferResponseDto replay(Transfer existing, CreateTransferRequestDto request) {
        if (!existing.matches(request.sourceAccountNumber(), request.targetAccountNumber(), request.amount())) {
            throw new BusinessException("Idempotency key already used with different data");
        }
        return TransferResponseDto.from(existing);
    }

    public TransferResponseDto findById(UUID id) {
        return transferRepository.findById(id)
                .map(TransferResponseDto::from)
                .orElseThrow(() -> new TransferNotFoundException(id));
    }

}
