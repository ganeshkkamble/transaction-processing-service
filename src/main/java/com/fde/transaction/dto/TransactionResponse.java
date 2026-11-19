
package com.fde.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fde.transaction.enums.TransactionStatus;

public record TransactionResponse(

    String transactionId,

    String requestId,

    String accountId,

    BigDecimal amount,

    String currency,

    Long sequenceNumber,

    TransactionStatus status,

    Integer retryCount,

    String message,

    LocalDateTime createdAt

) {}
