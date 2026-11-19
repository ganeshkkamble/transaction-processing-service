
package com.fde.transaction.dto;

import java.util.Map;

import com.fde.transaction.enums.TransactionStatus;

public record TransactionSummaryResponse(
        long totalTransactions,
        Map<TransactionStatus, Long> statusCounts
) {}
