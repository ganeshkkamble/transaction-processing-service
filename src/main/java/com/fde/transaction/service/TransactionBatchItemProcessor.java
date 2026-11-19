package com.fde.transaction.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fde.transaction.dto.TransactionRequest;
import com.fde.transaction.dto.TransactionResponse;

@Service
public class TransactionBatchItemProcessor {

    private final TransactionProcessingService processingService;

    public TransactionBatchItemProcessor(
            TransactionProcessingService processingService) {
        this.processingService = processingService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionResponse process(TransactionRequest request) {
        return processingService.submitTransaction(request);
    }
}