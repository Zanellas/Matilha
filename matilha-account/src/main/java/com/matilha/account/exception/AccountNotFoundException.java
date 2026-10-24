package com.matilha.account.exception;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String accountNumber) {
        super("Account " + accountNumber + " not found");
    }
    
}
