package com.fde.transaction.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fde.transaction.dto.BatchTransactionResponse;
import com.fde.transaction.dto.TransactionRequest;
import com.fde.transaction.dto.TransactionResponse;
import com.fde.transaction.enums.TransactionStatus;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

@Service
public class TransactionBatchProcessingService {

    private final TransactionBatchItemProcessor itemProcessor;
    private final Validator validator;

    public TransactionBatchProcessingService(
            TransactionBatchItemProcessor itemProcessor,
            Validator validator) {

        this.itemProcessor = itemProcessor;
        this.validator = validator;
    }

    public BatchTransactionResponse processBatch(
            List<TransactionRequest> requests) {

        List<TransactionResponse> results = new ArrayList<>();

        int successful = 0;
        int failed = 0;

        for (TransactionRequest request : requests) {

            // Validate each transaction independently
            Set<ConstraintViolation<TransactionRequest>> violations =
                    validator.validate(request);

            if (!violations.isEmpty()) {

                String errorMessage =
                        violations.iterator().next().getMessage();

                results.add(toFailedResponse(request, errorMessage));
                failed++;
                continue;
            }

            try {

                TransactionResponse response =
                        itemProcessor.process(request);

                results.add(response);

                if (response.status() == TransactionStatus.FAILED) {
                    failed++;
                } else {
                    successful++;
                }

            } catch (Exception exception) {

                // This item's independent transaction has rolled back.
                results.add(toFailedResponse(
                        request,
                        "Transaction processing failed"
                ));

                failed++;
            }
        }

        return new BatchTransactionResponse(
                requests.size(),
                successful,
                failed,
                results
        );
    }

    private TransactionResponse toFailedResponse(
            TransactionRequest request,
            String message) {

        return new TransactionResponse(
                request.transactionId(),
                request.requestId(),
                request.accountId(),
                request.amount(),
                request.currency(),
                request.sequenceNumber(),
                TransactionStatus.FAILED,
                0,
                message,
                null
        );
    }
}