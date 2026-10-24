package com.matilha.account.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.matilha.account.entity.AccountMovement;
import com.matilha.account.entity.MovementOperation;

public interface AccountMovementRepository extends JpaRepository<AccountMovement, UUID> {

    List<AccountMovement> findByOperationIdAndOperation(UUID operationId, MovementOperation operation);

}
