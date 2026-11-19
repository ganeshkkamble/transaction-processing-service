
package com.fde.transaction.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.fde.transaction.entity.TransactionEntity;
import com.fde.transaction.enums.TransactionStatus;

@Repository
public interface TransactionRepository
        extends JpaRepository<TransactionEntity, String> {

    // Find transaction using request ID
    Optional<TransactionEntity> findByRequestId(String requestId);

    // Check whether request ID already exists
    boolean existsByRequestId(String requestId);

    // Find transactions belonging to an account
    List<TransactionEntity> findByAccountId(String accountId);

    // Find transaction for a particular account and sequence
    Optional<TransactionEntity> findByAccountIdAndSequenceNumber(
            String accountId,
            Long sequenceNumber);

    // Find pending transactions for an account
    List<TransactionEntity> findByAccountIdAndStatusOrderBySequenceNumberAsc(
            String accountId,
            TransactionStatus status);

    // Find transactions by processing status
    List<TransactionEntity> findByStatus(TransactionStatus status);
    
    long countByStatus(TransactionStatus status);
    
    List<TransactionEntity>
    findByStatusOrderByCreatedAtAsc(TransactionStatus status);
    
    boolean existsByOriginalTransactionId(String originalTransactionId);
    
    boolean existsByOriginalTransactionIdAndTransactionIdNot(
            String originalTransactionId,
            String transactionId);
}
