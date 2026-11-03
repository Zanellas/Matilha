package com.matilha.notification.entity;

public enum FailureCode {
    INSUFFICIENT_BALANCE,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_INACTIVE,
    REJECTED_BY_ACCOUNT,
    INVALID_REQUEST;

    public static FailureCode fromCode(String code) {
        if (code == null) {
            return REJECTED_BY_ACCOUNT;
        }
        return switch (code) {
            case "INSUFFICIENT_BALANCE" -> INSUFFICIENT_BALANCE;
            case "ACCOUNT_NOT_FOUND" -> ACCOUNT_NOT_FOUND;
            case "ACCOUNT_INACTIVE" -> ACCOUNT_INACTIVE;
            case "INVALID_REQUEST" -> INVALID_REQUEST;
            default -> REJECTED_BY_ACCOUNT;
        };
    }
}
