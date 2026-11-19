
package com.fde.transaction.dto;

import java.math.BigDecimal;

import com.fde.transaction.enums.TransactionType;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TransactionRequest(

    @NotBlank(message = "Transaction ID is required")
    String transactionId,

    @NotBlank(message = "Request ID is required")
    String requestId,

    @NotBlank(message = "Account ID is required")
    String accountId,

    @NotNull(message = "Transaction type is required")
    TransactionType transactionType,

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    BigDecimal amount,

    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "^[A-Z]{3}$",
             message = "Currency must be a 3-letter uppercase code")
    String currency,

    @NotNull(message = "Sequence number is required")
    @DecimalMin(value = "1", message = "Sequence number must be positive")
    Long sequenceNumber,
    
    String originalTransactionId

) {}
