package com.matilha.transfer.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.matilha.transfer.entity.Transfer;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

}
