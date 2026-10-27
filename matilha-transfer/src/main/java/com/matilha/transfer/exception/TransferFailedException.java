package com.matilha.transfer.exception;

import com.matilha.transfer.dto.TransferResponseDto;

import lombok.Getter;

@Getter
public class TransferFailedException extends RuntimeException {

    private final TransferResponseDto transfer;

    public TransferFailedException(TransferResponseDto transfer) {
        super(transfer.failureReason());
        this.transfer = transfer;
    }

}
