package com.matilha.transfer.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.matilha.transfer.dto.TransferEvent;
import com.matilha.transfer.entity.FailureCode;
import com.matilha.transfer.entity.OutboxEvent;
import com.matilha.transfer.entity.Transfer;
import com.matilha.transfer.repository.OutboxEventRepository;
import com.matilha.transfer.repository.TransferRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class TransferFinisher {

    private final TransferRepository transferRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;

    @Transactional
    public Transfer complete(Transfer transfer, String targetOwnerId) {
        transfer.complete(targetOwnerId);
        return saveWithEvent(transfer);
    }

    @Transactional
    public Transfer fail(Transfer transfer, FailureCode code, String reason) {
        transfer.fail(code, reason);
        return saveWithEvent(transfer);
    }

    private Transfer saveWithEvent(Transfer transfer) {
        Transfer saved = transferRepository.save(transfer);
        TransferEvent event = TransferEvent.from(saved);
        outboxEventRepository.save(new OutboxEvent(event.eventId(), saved.getId(), event.eventType(),
                saved.getSourceAccountNumber(), jsonMapper.writeValueAsString(event)));
        return saved;
    }
}
