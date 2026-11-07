package com.matilha.transfer.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.matilha.transfer.client.AccountClient;
import com.matilha.transfer.dto.AccountTransferRequestDto;
import com.matilha.transfer.dto.AccountTransferResponseDto;
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
    private final TransferFinisher transferFinisher;

    public TransferResponseDto create(UUID idempotencyKey, CreateTransferRequestDto request, String ownerId) {
        Optional<Transfer> existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), request, ownerId);
        }

        Transfer transfer;
        try {
            transfer = transferRepository.save(new Transfer(ownerId, idempotencyKey, request.sourceAccountNumber(),
                    request.targetAccountNumber(), request.amount()));
        } catch (DataIntegrityViolationException ex) {
            Transfer winner = transferRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> ex);
            return replay(winner, request, ownerId);
        }

        return TransferResponseDto.from(process(transfer));
    }

    private TransferResponseDto replay(Transfer existing, CreateTransferRequestDto request, String ownerId) {
        if (!existing.isOwnedBy(ownerId)
                || !existing.matches(request.sourceAccountNumber(), request.targetAccountNumber(), request.amount())) {
            throw new BusinessException("Idempotency key already used with different data");
        }
        return TransferResponseDto.from(existing);
    }

    public TransferResponseDto findById(UUID id, String ownerId) {
        return transferRepository.findByIdAndOwnerId(id, ownerId)
                .map(TransferResponseDto::from)
                .orElseThrow(() -> new TransferNotFoundException(id));
    }

    public Transfer process(Transfer transfer) {
        AccountTransferRequestDto accountRequest = new AccountTransferRequestDto(
                transfer.getId(),
                transfer.getOwnerId(),
                transfer.getSourceAccountNumber(),
                transfer.getTargetAccountNumber(),
                transfer.getAmount());

        AccountTransferResponseDto accountResponse;
        try {
            accountResponse = accountClient.transfer(accountRequest);
        } catch (AccountTransferRejectedException ex) {
            return transferFinisher.fail(transfer, ex.getCode(), ex.getMessage());
        } catch (AccountUnavailableException ex) {
            log.warn("Account service unavailable, transfer {} stays PENDING", transfer.getId(), ex);
            return transfer;
        }

        return transferFinisher.complete(transfer, accountResponse.targetOwnerId());
    }

    @Transactional
    public List<Transfer> claimPending(Instant createdBefore, Duration lease, int limit) {
        Instant now = Instant.now();
        List<Transfer> claimed = transferRepository.findPendingToClaim(createdBefore, now, limit);
        claimed.forEach(transfer -> transfer.claimUntil(now.plus(lease)));
        return claimed;
    }

}
