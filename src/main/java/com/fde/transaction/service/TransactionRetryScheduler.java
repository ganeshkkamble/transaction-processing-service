
package com.fde.transaction.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fde.transaction.entity.TransactionEntity;
import com.fde.transaction.enums.TransactionStatus;
import com.fde.transaction.repository.TransactionRepository;

@Component
@ConditionalOnProperty(
        name = "app.transaction.retry-scheduler-enabled",
        havingValue = "true"
)
public class TransactionRetryScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(TransactionRetryScheduler.class);

    private final TransactionRepository transactionRepository;
    private final TransactionProcessingService processingService;

    public TransactionRetryScheduler(
            TransactionRepository transactionRepository,
            TransactionProcessingService processingService) {
        this.transactionRepository = transactionRepository;
        this.processingService = processingService;
    }

    @Scheduled(fixedDelayString =
            "${app.transaction.retry-delay-ms:5000}")
    public void retryPendingTransactions() {

        List<TransactionEntity> pendingTransactions =
                transactionRepository.findByStatusOrderByCreatedAtAsc(
                        TransactionStatus.RETRY_PENDING);

        log.info("Automatic retry scheduler started. Pending count={}",
                pendingTransactions.size());

        for (TransactionEntity transaction : pendingTransactions) {
            try {
                log.info("Automatic retry initiated. transactionId={}",
                        transaction.getTransactionId());

                processingService.retryTransaction(
                        transaction.getTransactionId());

            } catch (Exception ex) {
                log.error(
                        "Automatic retry failed. transactionId={}, reason={}",
                        transaction.getTransactionId(),
                        ex.getMessage(), ex);
            }
        }
    }
}
