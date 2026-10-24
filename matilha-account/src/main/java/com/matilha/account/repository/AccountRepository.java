package com.matilha.account.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.matilha.account.entity.Account;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    boolean existsByAccountNumber(String number);

    Optional<Account> findByAccountNumber(String accountNumber);

}
