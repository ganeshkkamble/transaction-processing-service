
package com.fde.transaction.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.fde.transaction.entity.ProcessingAttempt;

@Repository
public interface ProcessingAttemptRepository
        extends JpaRepository<ProcessingAttempt, Long> {

    // Get all attempts for a transaction
    List<ProcessingAttempt>
        findByTransactionTransactionIdOrderByAttemptNumberAsc(
                String transactionId);

    // Count attempts for a transaction
    long countByTransactionTransactionId(String transactionId);
}
