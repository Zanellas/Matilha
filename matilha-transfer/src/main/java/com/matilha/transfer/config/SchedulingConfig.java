package com.matilha.transfer.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "matilha.reconciler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

}
