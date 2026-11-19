
package com.fde.transaction.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fde.transaction.enums.TransactionStatus;
import com.fde.transaction.enums.TransactionType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
	    name = "transactions",
	    uniqueConstraints = {
	        @UniqueConstraint(
	            name = "uk_request_id",
	            columnNames = "request_id"
	        ),
	        @UniqueConstraint(
	            name = "uk_account_sequence",
	            columnNames = {"account_id", "sequence_number"}
	        )
	    }
	)
@Getter
@Setter
@NoArgsConstructor
public class TransactionEntity {

    @Id
    @Column(name = "transaction_id", nullable = false, length = 100)
    private String transactionId;

    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;

    @Column(name = "account_id", nullable = false, length = 50)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private TransactionType transactionType;

    @Column(name = "amount", nullable = false,
            precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "sequence_number", nullable = false)
    private Long sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TransactionStatus status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false,
    	    updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Column(name = "original_transaction_id", unique = true)
    private String originalTransactionId;

    @PrePersist
    public void onCreate() {

        LocalDateTime now = LocalDateTime.now();

        if (createdAt == null) {
            createdAt = now;
        }

        updatedAt = now;

        if (status == null) {
            status = TransactionStatus.RECEIVED;
        }

        if (retryCount == null) {
            retryCount = 0;
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
