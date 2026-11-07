package com.matilha.notification.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.matilha.notification.dto.NotificationResponseDto;
import com.matilha.notification.dto.TransferEventMessageDto;
import com.matilha.notification.entity.Notification;
import com.matilha.notification.entity.ProcessedEvent;
import com.matilha.notification.repository.NotificationRepository;
import com.matilha.notification.repository.ProcessedEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public void handle(TransferEventMessageDto event) {
        List<Notification> notifications = NotificationFactory.from(event);

        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Event {} already processed, skipping", event.eventId());
            return;
        }

        processedEventRepository.save(new ProcessedEvent(event.eventId()));
        notificationRepository.saveAll(notifications);

        notifications.forEach(n -> log.info("Notification {} for account {}: {}", n.getType(), n.getAccountNumber(),
                n.getMessage()));
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponseDto> findByAccount(String ownerId, String accountNumber, Pageable pageable) {
        Page<Notification> notifications = accountNumber == null
                ? notificationRepository.findByOwnerId(ownerId, pageable)
                : notificationRepository.findByOwnerIdAndAccountNumber(ownerId, accountNumber, pageable);
        return notifications.map(NotificationResponseDto::from);
    }

}
