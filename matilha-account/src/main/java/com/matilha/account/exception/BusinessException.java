package com.matilha.account.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode code;

    public BusinessException(String message) {
        this(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }

    public BusinessException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

}
