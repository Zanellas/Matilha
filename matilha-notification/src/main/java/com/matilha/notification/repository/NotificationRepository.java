package com.matilha.notification.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.matilha.notification.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    
    Page<Notification> findByAccountNumber(String accountNumber, Pageable pageable);
}
