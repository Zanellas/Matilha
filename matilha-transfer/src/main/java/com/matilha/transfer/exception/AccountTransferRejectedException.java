package com.matilha.transfer.exception;

public class AccountTransferRejectedException extends RuntimeException {
    public AccountTransferRejectedException(String reason) {
        super(reason);
    }
}
