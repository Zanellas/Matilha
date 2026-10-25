package com.matilha.transfer.exception;

public class AccountUnavailableException extends RuntimeException {
    public AccountUnavailableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
