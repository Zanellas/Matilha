package com.matilha.notification.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.matilha.notification.dto.NotificationResponseDto;
import com.matilha.notification.service.NotificationService;

import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<PagedModel<NotificationResponseDto>> findByAccount(
            @RequestParam @Pattern(regexp = "\\d{5}") String accountNumber, Pageable pageable) {
        return ResponseEntity.ok(new PagedModel<>(notificationService.findByAccount(accountNumber, pageable)));
    }

}
