package com.matilha.transfer.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.matilha.transfer.entity.OutboxEvent;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    
}
