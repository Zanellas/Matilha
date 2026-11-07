package com.matilha.transfer.exception;

import com.matilha.transfer.entity.FailureCode;

import lombok.Getter;

@Getter
public class AccountTransferRejectedException extends RuntimeException {

    private final FailureCode code;

    public AccountTransferRejectedException(FailureCode code, String reason) {
        super(reason);
        this.code = code;
    }
}
