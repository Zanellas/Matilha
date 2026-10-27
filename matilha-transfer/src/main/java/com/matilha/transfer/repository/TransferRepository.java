package com.matilha.transfer.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.matilha.transfer.entity.Transfer;
import com.matilha.transfer.entity.TransferStatus;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdempotencyKey(UUID idempotencyKey);

    List<Transfer> findTop50ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(TransferStatus status, Instant createdBefore);

    @Query(value = """
            SELECT * FROM transfers
            WHERE status = 'PENDING'
              AND created_at < :createdBefore
              AND (claimed_until IS NULL OR claimed_until < :now)
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Transfer> findPendingToClaim(Instant createdBefore, Instant now, int limit);

}
