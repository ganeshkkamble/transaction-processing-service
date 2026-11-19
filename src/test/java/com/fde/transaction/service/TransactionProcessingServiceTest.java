
package com.fde.transaction.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.*;

import com.fde.transaction.dto.TransactionRequest;
import com.fde.transaction.dto.TransactionResponse;
import com.fde.transaction.entity.Account;
import com.fde.transaction.entity.ProcessingAttempt;
import com.fde.transaction.entity.TransactionEntity;
import com.fde.transaction.enums.TransactionStatus;
import com.fde.transaction.enums.TransactionType;
import com.fde.transaction.repository.AccountRepository;
import com.fde.transaction.repository.ProcessingAttemptRepository;
import com.fde.transaction.repository.TransactionRepository;

import jakarta.validation.Validator;

@SpringBootTest
@TestPropertySource(properties = {
    "app.transaction.simulate-failure-transaction-id=TXN-4001",
    "app.transaction.max-retry-attempts=3",
    "app.transaction.retry-scheduler-enabled=false"
})
class TransactionProcessingServiceTest {

    @Autowired
    private TransactionProcessingService service;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ProcessingAttemptRepository attemptRepository;

    @Autowired
    private Validator validator;
    
    @MockitoSpyBean
    private ProcessingAttemptRepository processingAttemptRepositorySpy;

    @BeforeEach
    void setUp() {
        attemptRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        Account account = new Account();
        account.setAccountId("ACC-TEST");
        account.setBalance(new BigDecimal("100.00"));
        account.setLastSequence(0L);

        accountRepository.save(account);
    }

    // Test 1: Successful credit and duplicate prevention

    @Test
    void shouldProcessCreditAndPreventDuplicate() {

        TransactionRequest request = createRequest(
                "TXN-1001", "REQ-1001",
                TransactionType.CREDIT,
                "50.00", 1L);

        TransactionResponse firstResponse =
                service.submitTransaction(request);

        assertEquals(TransactionStatus.PROCESSED,
                firstResponse.status());

        TransactionResponse duplicateResponse =
                service.submitTransaction(request);

        assertEquals(TransactionStatus.DUPLICATE,
                duplicateResponse.status());

        Account account =
                accountRepository.findById("ACC-TEST").orElseThrow();

        assertEquals(0,
                new BigDecimal("150.00").compareTo(account.getBalance()));

        assertEquals(1,
                attemptRepository.countByTransactionTransactionId(
                        "TXN-1001"));
    }

    // Test 2: Out-of-order transaction processing

    @Test
    void shouldProcessTransactionsInSequenceOrder() {

        TransactionRequest sequenceTwo = createRequest(
                "TXN-2002", "REQ-2002",
                TransactionType.CREDIT,
                "50.00", 2L);

        TransactionResponse pendingResponse =
                service.submitTransaction(sequenceTwo);

        assertEquals(TransactionStatus.PENDING,
                pendingResponse.status());

        TransactionRequest sequenceOne = createRequest(
                "TXN-2001", "REQ-2001",
                TransactionType.CREDIT,
                "100.00", 1L);

        service.submitTransaction(sequenceOne);

        TransactionResponse sequenceTwoFinal =
                service.getTransaction("TXN-2002");

        assertEquals(TransactionStatus.PROCESSED,
                sequenceTwoFinal.status());

        Account account =
                accountRepository.findById("ACC-TEST").orElseThrow();

        assertEquals(0,
                new BigDecimal("250.00").compareTo(account.getBalance()));

        assertEquals(2L, account.getLastSequence());
    }

    // Test 3: Insufficient balance

    @Test
    void shouldFailDebitWhenBalanceIsInsufficient() {

        TransactionRequest request = createRequest(
                "TXN-3001", "REQ-3001",
                TransactionType.DEBIT,
                "200.00", 1L);

        TransactionResponse response =
                service.submitTransaction(request);

        assertEquals(TransactionStatus.FAILED,
                response.status());

        Account account =
                accountRepository.findById("ACC-TEST").orElseThrow();

        assertEquals(0,
                new BigDecimal("100.00").compareTo(account.getBalance()));

        assertEquals(1L, account.getLastSequence());
    }

    // Test 4: Retry after transient failure

    @Test
    void shouldRetryFailedTransactionSuccessfully() {

        TransactionRequest request = createRequest(
                "TXN-4001", "REQ-4001",
                TransactionType.CREDIT,
                "500.00", 1L);

        TransactionResponse firstResponse =
                service.submitTransaction(request);

        assertEquals(TransactionStatus.RETRY_PENDING,
                firstResponse.status());

        Account accountBeforeRetry =
                accountRepository.findById("ACC-TEST").orElseThrow();

        assertEquals(0,
                new BigDecimal("100.00").compareTo(
                        accountBeforeRetry.getBalance()));

        TransactionResponse retryResponse =
                service.retryTransaction("TXN-4001");

        assertEquals(TransactionStatus.PROCESSED,
                retryResponse.status());

        Account accountAfterRetry =
                accountRepository.findById("ACC-TEST").orElseThrow();

        assertEquals(0,
                new BigDecimal("600.00").compareTo(
                        accountAfterRetry.getBalance()));

        List<ProcessingAttempt> attempts =
                attemptRepository
                    .findByTransactionTransactionIdOrderByAttemptNumberAsc(
                            "TXN-4001");

        assertEquals(2, attempts.size());

        assertEquals(TransactionStatus.RETRY_PENDING,
                attempts.get(0).getStatus());

        assertEquals(TransactionStatus.PROCESSED,
                attempts.get(1).getStatus());
    }

    // Test 5: Invalid transaction validation

    @Test
    void shouldRejectNegativeTransactionAmount() {

        TransactionRequest request = createRequest(
                "TXN-5001", "REQ-5001",
                TransactionType.CREDIT,
                "-10.00", 1L);

        assertFalse(validator.validate(request).isEmpty());

        assertFalse(transactionRepository.existsById("TXN-5001"));
    }

    private TransactionRequest createRequest(
            String transactionId,
            String requestId,
            TransactionType type,
            String amount,
            Long sequenceNumber) {

        return new TransactionRequest(
                transactionId,
                requestId,
                "ACC-TEST",
                type,
                new BigDecimal(amount),
                "INR",
                sequenceNumber,
                null
        );
    }
    
    @Test
    void shouldPreventDuplicateProcessingForConcurrentRequests()
            throws Exception {

        TransactionRequest request = createRequest(
                "TXN-6001",
                "REQ-6001",
                TransactionType.CREDIT,
                "50.00",
                1L
        );

        CountDownLatch startSignal = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<TransactionResponse> firstRequest =
                    executor.submit(() -> {
                        startSignal.await();
                        return service.submitTransaction(request);
                    });

            Future<TransactionResponse> secondRequest =
                    executor.submit(() -> {
                        startSignal.await();
                        return service.submitTransaction(request);
                    });

            // Release both requests together
            startSignal.countDown();

            TransactionResponse response1 =
                    firstRequest.get(15, TimeUnit.SECONDS);

            TransactionResponse response2 =
                    secondRequest.get(15, TimeUnit.SECONDS);

            long processedCount = List.of(response1, response2)
                    .stream()
                    .filter(response ->
                            response.status() == TransactionStatus.PROCESSED)
                    .count();

            long duplicateCount = List.of(response1, response2)
                    .stream()
                    .filter(response ->
                            response.status() == TransactionStatus.DUPLICATE)
                    .count();

            assertEquals(1, processedCount);
            assertEquals(1, duplicateCount);

            Account account =
                    accountRepository.findById("ACC-TEST").orElseThrow();

            assertEquals(
                    0,
                    new BigDecimal("150.00").compareTo(account.getBalance())
            );

            assertEquals(
                    1,
                    attemptRepository.countByTransactionTransactionId(
                            "TXN-6001")
            );

        } finally {
            executor.shutdownNow();
        }
    }
    

	@Test
	void shouldRollbackEntireTransactionWhenAttemptSaveFails() {
	
	    TransactionRequest request = createRequest(
	            "TXN-7001",
	            "REQ-7001",
	            TransactionType.CREDIT,
	            "50.00",
	            1L
	    );
	
	    // Simulate database failure while saving processing attempt
	    doThrow(new RuntimeException("Simulated database failure"))
	            .when(processingAttemptRepositorySpy)
	            .save(any(ProcessingAttempt.class));
	
	    // Transaction processing should fail
	    assertThrows(RuntimeException.class, () ->
	            service.submitTransaction(request));
	
	    // Verify account balance was rolled back
	    Account account = accountRepository
	            .findById("ACC-TEST")
	            .orElseThrow();
	
	    assertEquals(
	            0,
	            new BigDecimal("100.00").compareTo(account.getBalance())
	    );
	
	    // Verify sequence was rolled back
	    assertEquals(0L, account.getLastSequence());
	
	    // Verify transaction record was rolled back
	    assertFalse(transactionRepository.existsById("TXN-7001"));
	
	    // Verify no processing attempt was persisted
	    assertEquals(
	            0,
	            attemptRepository.countByTransactionTransactionId("TXN-7001")
	    );
	}
	

	@Test
	void shouldMarkTransactionFailedWhenMaximumRetriesExceeded() {
	
	    // First transaction: simulated temporary failure
	    TransactionResponse firstResponse = service.submitTransaction(
	            createRequest(
	                    "TXN-4001",
	                    "REQ-7001",
	                    TransactionType.CREDIT,
	                    "50.00",
	                    1L
	            )
	    );
	
	    assertEquals(TransactionStatus.RETRY_PENDING,
	            firstResponse.status());
	
	    // Second transaction waits for sequence 1
	    TransactionResponse secondResponse = service.submitTransaction(
	            createRequest(
	                    "TXN-7002",
	                    "REQ-7002",
	                    TransactionType.CREDIT,
	                    "25.00",
	                    2L
	            )
	    );
	
	    assertEquals(TransactionStatus.PENDING,
	            secondResponse.status());
	
	    // Simulate exhausted retry attempts
	    TransactionEntity transaction = transactionRepository
	            .findById("TXN-4001")
	            .orElseThrow();
	
	    transaction.setRetryCount(3);
	    transactionRepository.save(transaction);
	
	    // Retry after maximum attempts
	    TransactionResponse retryResponse =
	            service.retryTransaction("TXN-4001");
	
	    assertEquals(TransactionStatus.FAILED,
	            retryResponse.status());
	
	    // Sequence 1 failed, sequence 2 should now process
	    Account account = accountRepository
	            .findById("ACC-TEST")
	            .orElseThrow();
	
	    assertEquals(2L, account.getLastSequence());
	    assertEquals(0,
	            new BigDecimal("125.00").compareTo(account.getBalance()));
	
	    // Verify final failed attempt was recorded
	    assertEquals(2,
	            attemptRepository.countByTransactionTransactionId("TXN-4001"));
	}

	@Test
	void shouldReverseCreditTransactionSuccessfully() {

	    TransactionRequest credit = createRequest(
	            "TXN-8001", "REQ-8001",
	            TransactionType.CREDIT, "500.00", 1L);

	    service.submitTransaction(credit);

	    TransactionRequest reversal = createReversalRequest(
	            "TXN-8002", "REQ-8002",
	            "500.00", 2L, "TXN-8001");

	    TransactionResponse response =
	            service.submitTransaction(reversal);

	    assertEquals(TransactionStatus.PROCESSED, response.status());
	}
	
	@Test
	void shouldReverseDebitTransactionSuccessfully() {

	    // Step 1: Credit 500
	    TransactionRequest credit = createRequest(
	            "TXN-8101",
	            "REQ-8101",
	            TransactionType.CREDIT,
	            "500.00",
	            1L
	    );

	    TransactionResponse creditResponse =
	    		service.submitTransaction(credit);

	    // Step 2: Debit 100
	    TransactionRequest debit = createRequest(
	            "TXN-8102",
	            "REQ-8102",
	            TransactionType.DEBIT,
	            "100.00",
	            2L
	    );

	    TransactionResponse debitResponse =
	            service.submitTransaction(debit);

	    // Step 3: Reverse the debit transaction
	    TransactionRequest reversal = createReversalRequest(
	            "TXN-8103",
	            "REQ-8103",
	            "100.00",
	            3L,
	            "TXN-8102"
	    );

	    TransactionResponse reversalResponse =
	            service.submitTransaction(reversal);

	    // Step 4: Fetch account
	    Account account = accountRepository.findById("ACC-TEST")
	            .orElseThrow();

	    // Step 5: Print debugging information
	    System.out.println("========== REVERSAL TEST DEBUG ==========");
	    System.out.println("Credit status: " + creditResponse.status());
	    System.out.println("Debit status: " + debitResponse.status());
	    System.out.println("Reversal status: " + reversalResponse.status());
	    System.out.println("Reversal message: " + reversalResponse.message());
	    System.out.println("Final account balance: " + account.getBalance());
	    System.out.println("Last sequence: " + account.getLastSequence());
	    System.out.println("=========================================");

	    // Step 6: Verify transaction statuses
	    assertEquals(TransactionStatus.PROCESSED, creditResponse.status());
	    assertEquals(TransactionStatus.PROCESSED, debitResponse.status());
	    assertEquals(TransactionStatus.PROCESSED, reversalResponse.status());

	    // Step 7: Verify final balance
	    assertEquals(
	            0,
	            new BigDecimal("600.00").compareTo(account.getBalance())
	    );

	    // Step 8: Verify sequence
	    assertEquals(3L, account.getLastSequence());
	}
	
	@Test
	void shouldFailReversalWhenOriginalTransactionDoesNotExist() {

	    TransactionRequest reversalRequest = new TransactionRequest(
	            "TXN-8201",
	            "REQ-8201",
	            "ACC-TEST",
	            TransactionType.REVERSAL,
	            new BigDecimal("100.00"),
	            "INR",
	            1L,
	            "TXN-9999"
	    );

	    TransactionResponse response =
	            service.submitTransaction(reversalRequest);

	    assertEquals(TransactionStatus.FAILED, response.status());

	    assertEquals("Original transaction not found",
	            response.message());

	    Account account = accountRepository.findById("ACC-TEST")
	            .orElseThrow();

	    assertEquals(0,
	            new BigDecimal("100.00").compareTo(account.getBalance()));

	    assertEquals(1L, account.getLastSequence());
	}
	
	private TransactionRequest createReversalRequest(
	        String transactionId,
	        String requestId,
	        String amount,
	        Long sequenceNumber,
	        String originalTransactionId) {

	    return new TransactionRequest(
	            transactionId,
	            requestId,
	            "ACC-TEST",
	            TransactionType.REVERSAL,
	            new BigDecimal(amount),
	            "INR",
	            sequenceNumber,
	            originalTransactionId
	    );
	}

}
