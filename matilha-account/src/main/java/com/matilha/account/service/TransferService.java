package com.matilha.account.service;

import org.springframework.stereotype.Service;

import com.matilha.account.repository.AccountMovementRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TransferService {
    
    private final AccountMovementRepository accountMovementRepository;
}
