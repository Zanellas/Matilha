package com.matilha.transfer.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.matilha.transfer.entity.OutboxEvent;
import com.matilha.transfer.repository.OutboxEventRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;

    @Transactional
    public List<OutboxEvent> claimUnpublished(Duration lease, int limit) {
        Instant now = Instant.now();
        List<OutboxEvent> claimed = outboxEventRepository.findUnpublishedToClaim(now, limit);
        claimed.forEach(event -> event.claimUntil(now.plus(lease)));
        return claimed;
    }

    @Transactional
    public void markPublished(UUID eventId) {
        outboxEventRepository.findById(eventId)
                .filter(event -> event.getPublishedAt() == null)
                .ifPresent(event -> event.markPublished(Instant.now()));
    }

    @Transactional
    public void registerFailure(UUID eventId, String error) {
        outboxEventRepository.findById(eventId).ifPresent(event -> event.registerFailure(error));
    }
}
