package com.matilha.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.matilha.notification.exception.BusinessException;
import com.matilha.notification.exception.UnsupportedEventException;

import tools.jackson.core.JacksonException;

@Configuration
public class KafkaConfig {

    public static final String TRANSFER_EVENTS_TOPIC = "matilha.transfer.events";
    public static final String TRANSFER_EVENTS_DLT = "matilha.transfer.events.DLT";

    @Bean
    NewTopic transferEventsDlt() {
        return TopicBuilder.name(TRANSFER_EVENTS_DLT).partitions(3).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(TRANSFER_EVENTS_DLT, record.partition()));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        handler.addNotRetryableExceptions(
                JacksonException.class,
                UnsupportedEventException.class,
                BusinessException.class);
        return handler;
    }
}
