package com.matilha.transfer.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {
    
    public static final String TRANSFER_EVENTS_TOPIC = "matilha.transfer.events";

    @Bean 
    NewTopic transferEventsTopi() {
        return TopicBuilder.name(TRANSFER_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }
}
