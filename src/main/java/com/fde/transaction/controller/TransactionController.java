
package com.fde.transaction.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.fde.transaction.dto.BatchTransactionResponse;
import com.fde.transaction.dto.TransactionRequest;
import com.fde.transaction.dto.TransactionResponse;
import com.fde.transaction.dto.TransactionSummaryResponse;
import com.fde.transaction.service.TransactionBatchProcessingService;
import com.fde.transaction.service.TransactionProcessingService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/transactions")
@Validated
public class TransactionController {

    private final TransactionProcessingService processingService;
    private final TransactionBatchProcessingService batchProcessingService;

    public TransactionController(
            TransactionProcessingService processingService,
            TransactionBatchProcessingService batchProcessingService) {

        this.processingService = processingService;
        this.batchProcessingService = batchProcessingService;
    }

    // Submit transaction
    @PostMapping
    public ResponseEntity<TransactionResponse> submitTransaction(
            @Valid @RequestBody TransactionRequest request) {

        TransactionResponse response =
                processingService.submitTransaction(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Get transaction details
    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> getTransaction(
            @PathVariable String id) {

        return ResponseEntity.ok(
                processingService.getTransaction(id));
    }

    // Get processing summary
    @GetMapping("/summary")
    public ResponseEntity<TransactionSummaryResponse> getSummary() {

        return ResponseEntity.ok(
                processingService.getSummary());
    }
    
    @PostMapping("/{id}/retry")
    public ResponseEntity<TransactionResponse> retryTransaction(
            @PathVariable String id) {

        return ResponseEntity.ok(
                processingService.retryTransaction(id));
    }
    
    @PostMapping("/batch")
    public ResponseEntity<BatchTransactionResponse> processBatch(
            @RequestBody List<TransactionRequest> requests) {

        return ResponseEntity.ok(
                batchProcessingService.processBatch(requests)
        );
    }
}
