package com.fde.transaction.dto;

import java.util.List;

public record BatchTransactionResponse(
        int totalRecords,
        int successfulRecords,
        int failedRecords,
        List<TransactionResponse> results
) {
}