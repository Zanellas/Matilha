package com.matilha.notification.component;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.matilha.notification.config.KafkaConfig;
import com.matilha.notification.dto.TransferEventMessage;
import com.matilha.notification.service.NotificationService;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

@Component 
@RequiredArgsConstructor 
public class TransferEventListener {
    
    private final JsonMapper jsonMapper;
    private final NotificationService notificationService;

    @KafkaListener(topics = KafkaConfig.TRANSFER_EVENTS_TOPIC)
    public void onMessage(String payload) {
        TransferEventMessage event = jsonMapper.readValue(payload, TransferEventMessage.class);
        notificationService.handle(event);
    }
}
