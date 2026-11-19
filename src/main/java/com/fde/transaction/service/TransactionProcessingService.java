
package com.fde.transaction.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fde.transaction.dto.TransactionRequest;
import com.fde.transaction.dto.TransactionResponse;
import com.fde.transaction.dto.TransactionSummaryResponse;
import com.fde.transaction.entity.Account;
import com.fde.transaction.entity.ProcessingAttempt;
import com.fde.transaction.entity.TransactionEntity;
import com.fde.transaction.enums.TransactionStatus;
import com.fde.transaction.enums.TransactionType;
import com.fde.transaction.repository.AccountRepository;
import com.fde.transaction.repository.ProcessingAttemptRepository;
import com.fde.transaction.repository.TransactionRepository;

@Service
public class TransactionProcessingService {
	
	private static final Logger log =
	        LoggerFactory.getLogger(TransactionProcessingService.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final ProcessingAttemptRepository attemptRepository;
    private final TransactionMetrics transactionMetrics;

    public TransactionProcessingService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            ProcessingAttemptRepository attemptRepository,
            TransactionMetrics transactionMetrics
            ) {

        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.attemptRepository = attemptRepository;
        this.transactionMetrics = transactionMetrics;
    }
    
    @Value("${app.transaction.max-retry-attempts:3}")
    private int maxRetryAttempts;

    @Value("${app.transaction.simulate-failure-transaction-id:}")
    private String simulateFailureTransactionId;

    @Transactional
    public TransactionResponse submitTransaction(TransactionRequest request) {
    	
    	log.info("Transaction submission received. transactionId={}, accountId={}, sequence={}",
    	        request.transactionId(),
    	        request.accountId(),
    	        request.sequenceNumber());

        // 1. Check duplicate transaction ID
        Optional<TransactionEntity> existingTransaction =
                transactionRepository.findById(request.transactionId());

        if (existingTransaction.isPresent()) {
        	log.warn("Duplicate transaction ID detected. transactionId={}",
                    request.transactionId());
            validateSameRequest(existingTransaction.get(), request);
            return toResponse(
                    existingTransaction.get(),
                    TransactionStatus.DUPLICATE,
                    "Transaction already received");
        }
        log.warn("Duplicate transaction detected. transactionId={}",
                request.transactionId());

        // 2. Check duplicate request ID
        Optional<TransactionEntity> existingRequest =
                transactionRepository.findByRequestId(request.requestId());

        if (existingRequest.isPresent()) {
        	log.warn("Duplicate request ID detected. requestId={}",
                    request.requestId());
            validateSameRequest(existingRequest.get(), request);
            return toResponse(
                    existingRequest.get(),
                    TransactionStatus.DUPLICATE,
                    "Request already received");
        }
        

        // 3. Lock the account before checking its sequence and balance
        Account account = accountRepository
                .findByAccountIdForUpdate(request.accountId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Account not found: " + request.accountId()));
        log.debug("Account lock acquired. accountId={}, lastSequence={}",
                account.getAccountId(),
                account.getLastSequence());
	     
	     // Recheck idempotency after acquiring the account lock.
	     // Another request may have committed while this request was waiting.
	
	     Optional<TransactionEntity> latestTransaction =
	             transactionRepository.findById(request.transactionId());
	
	     if (latestTransaction.isPresent()) {
	
	         log.warn("Concurrent duplicate transaction detected after account lock. transactionId={}",
	                 request.transactionId());
	
	         validateSameRequest(latestTransaction.get(), request);
	
	         return toResponse(
	                 latestTransaction.get(),
	                 TransactionStatus.DUPLICATE,
	                 "Transaction already received"
	         );
	     }
	
	     Optional<TransactionEntity> latestRequest =
	             transactionRepository.findByRequestId(request.requestId());
	
	     if (latestRequest.isPresent()) {
	
	         log.warn("Concurrent duplicate request detected after account lock. requestId={}",
	                 request.requestId());
	
	         validateSameRequest(latestRequest.get(), request);
	
	         return toResponse(
	                 latestRequest.get(),
	                 TransactionStatus.DUPLICATE,
	                 "Request already received"
	         );
	     }

        // 4. Check whether this sequence was already submitted
        Optional<TransactionEntity> sameSequence =
                transactionRepository.findByAccountIdAndSequenceNumber(
                        request.accountId(), request.sequenceNumber());

        if (sameSequence.isPresent()) {
        	log.warn("Sequence number already exists. accountId={}, sequence={}",
        	        request.accountId(),
        	        request.sequenceNumber());
            throw new IllegalStateException(
                    "Sequence number already exists for this account");
        }

        if (request.sequenceNumber() <= account.getLastSequence()) {
        	log.warn("Out-of-date sequence received. accountId={}, requestedSequence={}, lastSequence={}",
        	        request.accountId(),
        	        request.sequenceNumber(),
        	        account.getLastSequence());
            throw new IllegalStateException(
                    "Sequence number is older than the last processed sequence");
        }

        // 5. Convert request to entity and save
        TransactionEntity transaction = new TransactionEntity();

        transaction.setTransactionId(request.transactionId());
        transaction.setRequestId(request.requestId());
        transaction.setAccountId(request.accountId());
        transaction.setTransactionType(request.transactionType());
        transaction.setAmount(request.amount());
        transaction.setCurrency(request.currency());
        transaction.setSequenceNumber(request.sequenceNumber());
        transaction.setRetryCount(0);
        transaction.setCreatedAt(LocalDateTime.now());
        transaction.setOriginalTransactionId(request.originalTransactionId());

        if (request.sequenceNumber() > account.getLastSequence() + 1) {
            transaction.setStatus(TransactionStatus.PENDING);

            transactionRepository.save(transaction);
            log.info("Transaction moved to PENDING. transactionId={}, expectedSequence={}, receivedSequence={}",
                    transaction.getTransactionId(),
                    account.getLastSequence() + 1,
                    transaction.getSequenceNumber());

            return toResponse(
                    transaction,
                    TransactionStatus.PENDING,
                    "Waiting for an earlier transaction sequence");
        }

        transaction.setStatus(TransactionStatus.RECEIVED);
        transactionRepository.save(transaction);

        log.info("Starting transaction processing. transactionId={}",
                transaction.getTransactionId());
        // 6. Process this transaction and any now-ready pending transactions
        processTransaction(account, transaction);
        processPendingTransactions(account);
        
        log.info("Transaction submission completed. transactionId={}, status={}, balance={}",
                transaction.getTransactionId(),
                transaction.getStatus(),
                account.getBalance());

        return toResponse(
                transaction,
                transaction.getStatus(),
                transaction.getFailureReason() == null
                        ? "Transaction processing completed"
                        : transaction.getFailureReason());
    }


	private void processPendingTransactions(Account account) {
	
	    log.info("Checking pending transactions. accountId={}, lastSequence={}",
	            account.getAccountId(),
	            account.getLastSequence());
	
	    while (true) {
	
	        Long expectedSequence = account.getLastSequence() + 1;
	
	        log.debug("Looking for pending transaction. accountId={}, expectedSequence={}",
	                account.getAccountId(),
	                expectedSequence);
	
	        Optional<TransactionEntity> pendingTransaction =
	                transactionRepository
	                        .findByAccountIdAndSequenceNumber(
	                                account.getAccountId(), expectedSequence);
	
	        // No transaction found for the expected sequence
	        if (pendingTransaction.isEmpty()) {
	
	            log.debug("No transaction found for expected sequence. accountId={}, expectedSequence={}",
	                    account.getAccountId(),
	                    expectedSequence);
	
	            return;
	        }
	
	        TransactionEntity transaction = pendingTransaction.get();
	
	        // Transaction exists but is not in PENDING status
	        if (transaction.getStatus() != TransactionStatus.PENDING) {
	
	            log.debug("Transaction is not in PENDING status. transactionId={}, status={}, expectedSequence={}",
	                    transaction.getTransactionId(),
	                    transaction.getStatus(),
	                    expectedSequence);
	
	            return;
	        }
	
	        log.info("Processing pending transaction. transactionId={}, accountId={}, sequence={}",
	                transaction.getTransactionId(),
	                account.getAccountId(),
	                transaction.getSequenceNumber());
	
	        processTransaction(account, transaction);
	
	        log.info("Pending transaction processing completed. transactionId={}, status={}, lastSequence={}",
	                transaction.getTransactionId(),
	                transaction.getStatus(),
	                account.getLastSequence());
	
	        // If processing resulted in a temporary failure, stop.
	        // The sequence is not consumed, so later transactions must wait.
	        if (transaction.getStatus() == TransactionStatus.RETRY_PENDING) {
	
	            log.warn("Pending transaction requires retry. Stopping pending processing. transactionId={}, sequence={}",
	                    transaction.getTransactionId(),
	                    transaction.getSequenceNumber());
	
	            return;
	        }
	    }
	}

	private void processTransaction(
	        Account account,
	        TransactionEntity transaction) {
	
	    transaction.setStatus(TransactionStatus.PROCESSING);
	
	    int attemptNumber =
	            (int) attemptRepository.countByTransactionTransactionId(
	                    transaction.getTransactionId()) + 1;
	
	    log.info("Transaction processing started. transactionId={}, attempt={}",
	            transaction.getTransactionId(), attemptNumber);
	
	    // Simulate a transient failure on the first attempt
	    if (transaction.getTransactionId().equals(simulateFailureTransactionId)
	            && attemptNumber == 1) {
	
	        transaction.setRetryCount(transaction.getRetryCount() + 1);
	        transaction.setStatus(TransactionStatus.RETRY_PENDING);
	        transaction.setFailureReason(
	                "Simulated temporary processing failure");
	
	        saveAttempt(transaction, attemptNumber,
	                TransactionStatus.RETRY_PENDING,
	                "Simulated temporary processing failure");
	
	        transactionRepository.save(transaction);
	        return;
	    }
	
	    BigDecimal currentBalance = account.getBalance();
	    BigDecimal amount = transaction.getAmount();
	
	    TransactionStatus finalStatus;
	    String errorMessage = null;
	
	    if (transaction.getTransactionType() == TransactionType.CREDIT) {
	
	        account.setBalance(currentBalance.add(amount));
	        finalStatus = TransactionStatus.PROCESSED;
	
	    } else if (transaction.getTransactionType() == TransactionType.DEBIT) {
	
	        if (currentBalance.compareTo(amount) < 0) {
	            finalStatus = TransactionStatus.FAILED;
	            errorMessage = "Insufficient account balance";
	        } else {
	            account.setBalance(currentBalance.subtract(amount));
	            finalStatus = TransactionStatus.PROCESSED;
	        }
	
	    } else if (transaction.getTransactionType() == TransactionType.REVERSAL) {
	
	        try {
	            // Validate original transaction reference
	            String originalTransactionId =
	                    transaction.getOriginalTransactionId();
	
	            if (originalTransactionId == null
	                    || originalTransactionId.isBlank()) {
	                throw new IllegalArgumentException(
	                        "Original transaction ID is required for reversal");
	            }
	
	            TransactionEntity original = transactionRepository
	                    .findById(originalTransactionId)
	                    .orElseThrow(() -> new IllegalArgumentException(
	                            "Original transaction not found"));
	
	            // Validate reversal business rules
	            validateReversalRequest(transaction, original);
	
	            // Prevent reversing the same transaction twice
	            boolean alreadyReversed =
	                    transactionRepository
	                            .existsByOriginalTransactionIdAndTransactionIdNot(
	                                    originalTransactionId,
	                                    transaction.getTransactionId());
	
	            if (alreadyReversed) {
	                throw new IllegalArgumentException(
	                        "Original transaction has already been reversed");
	            }
	
	            // Reverse the original financial operation
	            if (original.getTransactionType() == TransactionType.CREDIT) {
	
	                if (currentBalance.compareTo(amount) < 0) {
	                    throw new IllegalArgumentException(
	                            "Insufficient balance to reverse original credit");
	                }
	
	                account.setBalance(currentBalance.subtract(amount));
	
	            } else if (original.getTransactionType() == TransactionType.DEBIT) {
	
	                account.setBalance(currentBalance.add(amount));
	
	            } else {
	                throw new IllegalArgumentException(
	                        "Reversal of another reversal is not allowed");
	            }
	
	            finalStatus = TransactionStatus.PROCESSED;
	
	        } catch (IllegalArgumentException ex) {
	
	            finalStatus = TransactionStatus.FAILED;
	            errorMessage = ex.getMessage();
	
	            log.warn("Reversal validation failed. transactionId={}, reason={}",
	                    transaction.getTransactionId(), errorMessage);
	        }
	
	    } else {
	
	        finalStatus = TransactionStatus.FAILED;
	        errorMessage = "Unsupported transaction type";
	    }
	
	    // Business failures consume the sequence.
	    // Transient failures do not reach this point.
	    account.setLastSequence(transaction.getSequenceNumber());
	
	    transaction.setStatus(finalStatus);
	    transaction.setFailureReason(errorMessage);
	
	    if (finalStatus == TransactionStatus.PROCESSED) {
	        transactionMetrics.incrementProcessed();
	        transaction.setFailureReason(null);
	    } else if (finalStatus == TransactionStatus.FAILED) {
	        transactionMetrics.incrementFailed();
	    }
	
	    accountRepository.save(account);
	    transactionRepository.save(transaction);
	
	    saveAttempt(transaction, attemptNumber, finalStatus, errorMessage);
	
	    log.info("Transaction processing completed. transactionId={}, status={}, balance={}",
	            transaction.getTransactionId(),
	            finalStatus,
	            account.getBalance());
	}
    
    private void saveAttempt(
            TransactionEntity transaction,
            int attemptNumber,
            TransactionStatus status,
            String errorMessage) {

        ProcessingAttempt attempt = new ProcessingAttempt();

        attempt.setTransaction(transaction);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setStatus(status);
        attempt.setErrorMessage(errorMessage);

        attemptRepository.save(attempt);
    }

    private void validateSameRequest(
            TransactionEntity existing,
            TransactionRequest request) {

        boolean sameBusinessDetails =
                existing.getAccountId().equals(request.accountId())
                && existing.getTransactionType() == request.transactionType()
                && existing.getAmount().compareTo(request.amount()) == 0
                && existing.getCurrency().equals(request.currency())
                && existing.getSequenceNumber().equals(request.sequenceNumber())
                && Objects.equals(
                        existing.getOriginalTransactionId(),
                        request.originalTransactionId()
                );

        if (!sameBusinessDetails) {
            throw new IllegalStateException(
                    "Duplicate identifier received with different transaction details");
        }
    }

    private TransactionResponse toResponse(
            TransactionEntity transaction,
            TransactionStatus responseStatus,
            String message) {

        return new TransactionResponse(
                transaction.getTransactionId(),
                transaction.getRequestId(),
                transaction.getAccountId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getSequenceNumber(),
                responseStatus,
                transaction.getRetryCount(),
                message,
                transaction.getCreatedAt());
    }
    

	@Transactional(readOnly = true)
	public TransactionResponse getTransaction(String transactionId) {
	
	    TransactionEntity transaction = transactionRepository
	            .findById(transactionId)
	            .orElseThrow(() -> new IllegalArgumentException(
	                    "Transaction not found: " + transactionId));
	
	    return toResponse(
	            transaction,
	            transaction.getStatus(),
	            transaction.getFailureReason() == null
	                    ? "Transaction details retrieved"
	                    : transaction.getFailureReason());
	}
	
	@Transactional(readOnly = true)
	public TransactionSummaryResponse getSummary() {
	
	    Map<TransactionStatus, Long> statusCounts =
	            new EnumMap<>(TransactionStatus.class);
	
	    for (TransactionStatus status : TransactionStatus.values()) {
	        statusCounts.put(
	                status,
	                transactionRepository.countByStatus(status));
	    }
	
	    long total = transactionRepository.count();
	
	    return new TransactionSummaryResponse(total, statusCounts);
	}
	


	@Transactional
	public TransactionResponse retryTransaction(String transactionId) {
	
	    log.info("Transaction retry requested. transactionId={}", transactionId);
	
	    // 1. Fetch transaction
	    TransactionEntity transaction = transactionRepository
	            .findById(transactionId)
	            .orElseThrow(() -> {
	                log.warn("Retry failed. Transaction not found. transactionId={}",
	                        transactionId);
	                return new IllegalArgumentException(
	                        "Transaction not found: " + transactionId);
	            });
	
	    log.info("Transaction found. transactionId={}, status={}, retryCount={}",
	            transactionId,
	            transaction.getStatus(),
	            transaction.getRetryCount());
	
	    // 2. Validate transaction status
	    if (transaction.getStatus() != TransactionStatus.RETRY_PENDING) {
	        log.warn("Retry rejected. transactionId={}, status={}",
	                transactionId, transaction.getStatus());
	
	        throw new IllegalStateException(
	                "Only RETRY_PENDING transactions can be retried");
	    }
	
	    // 3. Acquire account lock
	    Account account = accountRepository
	            .findByAccountIdForUpdate(transaction.getAccountId())
	            .orElseThrow(() -> {
	                log.error("Account not found. transactionId={}, accountId={}",
	                        transactionId, transaction.getAccountId());
	                return new IllegalArgumentException(
	                        "Account not found: " + transaction.getAccountId());
	            });
	
	    log.debug("Account lock acquired. transactionId={}, accountId={}, lastSequence={}",
	            transactionId,
	            account.getAccountId(),
	            account.getLastSequence());
	
	    // 4. Validate transaction sequence
	    if (transaction.getSequenceNumber() != account.getLastSequence() + 1) {
	        log.warn("Retry postponed. transactionId={}, expectedSequence={}, actualSequence={}",
	                transactionId,
	                account.getLastSequence() + 1,
	                transaction.getSequenceNumber());
	
	        throw new IllegalStateException(
	                "Earlier transaction sequence is not yet completed");
	    }
	
	    // 5. Check maximum retry attempts
	    if (transaction.getRetryCount() >= maxRetryAttempts) {
	
	        log.error("Maximum retry attempts exceeded. transactionId={}, retryCount={}",
	                transactionId, transaction.getRetryCount());
	
	        String failureReason = "Maximum retry attempts exceeded";
	
	        transaction.setStatus(TransactionStatus.FAILED);
	        transaction.setFailureReason(failureReason);
	
	        // Consume sequence for terminal failure
	        account.setLastSequence(transaction.getSequenceNumber());
	
	        accountRepository.save(account);
	        transactionRepository.save(transaction);
	
	        // Record final failed attempt
	        int attemptNumber =
	                (int) attemptRepository.countByTransactionTransactionId(transactionId) + 1;
	
	        saveAttempt(
	                transaction,
	                attemptNumber,
	                TransactionStatus.FAILED,
	                failureReason
	        );
	
	        log.error("Transaction permanently failed. transactionId={}, lastSequence={}",
	                transactionId, account.getLastSequence());
	
	        // Process subsequent pending transactions
	        processPendingTransactions(account);
	
	        return toResponse(
	                transaction,
	                TransactionStatus.FAILED,
	                failureReason
	        );
	    }
	
	    // 6. Process retry
	    log.info("Retry processing started. transactionId={}, attempt={}",
	            transactionId,
	            transaction.getRetryCount() + 1);
	
	    processTransaction(account, transaction);
	
	    log.info("Retry processing completed. transactionId={}, status={}, retryCount={}",
	            transactionId,
	            transaction.getStatus(),
	            transaction.getRetryCount());
	
	    // 7. Process subsequent pending transactions
	    if (transaction.getStatus() == TransactionStatus.PROCESSED
	            || transaction.getStatus() == TransactionStatus.FAILED) {
	
	        log.info("Checking pending transactions. transactionId={}, accountId={}",
	                transactionId, account.getAccountId());
	
	        processPendingTransactions(account);
	    }
	
	    // 8. Return response
	    log.info("Retry request completed. transactionId={}, finalStatus={}, retryCount={}",
	            transactionId,
	            transaction.getStatus(),
	            transaction.getRetryCount());
	
	    return toResponse(
	            transaction,
	            transaction.getStatus(),
	            transaction.getFailureReason() == null
	                    ? "Retry processing completed"
	                    : transaction.getFailureReason()
	    );
	}

	private void validateReversalRequest(
	        TransactionEntity reversal,
	        TransactionEntity original) {

	    if (original == null) {
	        throw new IllegalArgumentException(
	                "Original transaction not found");
	    }

	    if (original.getStatus() != TransactionStatus.PROCESSED) {
	        throw new IllegalArgumentException(
	                "Only processed transactions can be reversed");
	    }

	    if (original.getTransactionType() == TransactionType.REVERSAL) {
	        throw new IllegalArgumentException(
	                "A reversal transaction cannot be reversed");
	    }

	    if (!original.getAccountId().equals(reversal.getAccountId())) {
	        throw new IllegalArgumentException(
	                "Reversal account must match original transaction account");
	    }

	    if (original.getAmount().compareTo(reversal.getAmount()) != 0) {
	        throw new IllegalArgumentException(
	                "Reversal amount must match original transaction amount");
	    }

	    if (!original.getCurrency().equals(reversal.getCurrency())) {
	        throw new IllegalArgumentException(
	                "Reversal currency must match original transaction currency");
	    }
	}
	
}
