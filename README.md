# Idempotent Background Transaction Processing Service

## 1. Project Overview

This project implements a Spring Boot REST service for processing financial transactions reliably.

It supports:

- CREDIT, DEBIT, and REVERSAL transactions.
- Idempotent transaction submission to prevent duplicate processing.
- Sequence-based transaction ordering per account.
- Background retry handling for transient failures.
- Batch transaction submission.
- Transaction status tracking and processing-attempt history.
- Input validation and centralized exception handling.
- Application logging and Actuator monitoring.

## 2. Technology Stack

- Java 17
- Spring Boot
- Spring Data JPA / Hibernate
- Spring Validation
- H2 Database
- Maven
- JUnit 5
- Spring Boot Actuator
- Micrometer

## 3. Architecture

The application follows a layered architecture:

```text
Client / Postman
       |
       v
TransactionController
       |
       v
TransactionProcessingService
       |
       +----------------------+
       |                      |
       v                      v
AccountRepository      TransactionRepository
                              |
                              v
                    ProcessingAttemptRepository

TransactionRetryScheduler
       |
       v
TransactionProcessingService
```

### Main components

| Component | Responsibility |
|---|---|
| Controller | Exposes transaction REST APIs |
| TransactionProcessingService | Handles transaction validation, ordering, and processing |
| TransactionBatchProcessingService | Handles batch submissions |
| TransactionBatchItemProcessor | Processes each batch item in an independent transaction |
| TransactionRetryScheduler | Periodically retries eligible failed transactions |
| AccountRepository | Retrieves and locks account records |
| TransactionRepository | Stores and retrieves transaction records |
| ProcessingAttemptRepository | Maintains processing-attempt history |

## 4. Transaction Lifecycle

The service uses the following transaction statuses:

| Status | Description |
|---|---|
| RECEIVED | Transaction has been accepted |
| PENDING | Waiting for an earlier sequence number |
| PROCESSING | Currently being processed |
| PROCESSED | Successfully completed |
| DUPLICATE | Request matches an already submitted transaction |
| RETRY_PENDING | Transient failure; waiting for retry |
| FAILED | Transaction could not be processed |

## 5. Idempotency

The service prevents duplicate financial processing using:

- Unique transaction ID.
- Unique request ID / idempotency key.
- Database uniqueness constraints.
- Duplicate request validation.

When the same transaction is submitted again with matching business details, the service identifies it as a duplicate and does not apply the balance change again.

If an existing transaction ID or request ID is reused with different business details, the request is treated as a conflict.

## 6. Sequence-Based Transaction Ordering

Transactions are processed in sequence order for each account.

For example:

1. Sequence 2 arrives before sequence 1.
2. Sequence 2 is stored with `PENDING` status.
3. Sequence 1 arrives and is processed.
4. The service checks for the next expected sequence.
5. Sequence 2 is then processed.

The account's `lastSequence` field tracks the latest consumed sequence number.

### Failure policy

- Business failures, such as insufficient balance, consume the sequence number.
- Transient technical failures do not consume the sequence number while waiting for retry.
- If the retry limit is exhausted, the transaction becomes terminally `FAILED` and its sequence is consumed so later transactions can proceed.

## 7. Transaction Types

### CREDIT

Adds the transaction amount to the account balance.

### DEBIT

Subtracts the transaction amount if sufficient balance is available.

### REVERSAL

Reverses an eligible previously processed transaction.

Reversal validation includes:

- Original transaction must exist.
- Original transaction must have `PROCESSED` status.
- Original transaction cannot itself be a reversal.
- Both transactions must belong to the same account.
- Amount and currency must match.
- An original transaction can only be reversed once.

A reversal of a CREDIT subtracts the original amount. A reversal of a DEBIT adds the original amount back.

## 8. Retry Handling

Transient processing failures are marked `RETRY_PENDING`.

The retry scheduler periodically identifies eligible transactions and invokes the retry service.

Configuration properties:

```yaml
app:
  transaction:
    max-retry-attempts: 3
    retry-delay-ms: 5000
    simulate-failure-transaction-id: TXN-4001
    retry-scheduler-enabled: true
```

The simulated failure configuration is intended for testing retry behavior. Disable or remove it in a production deployment.

Every processing attempt is recorded with its attempt number, status, error message, and timestamp.

## 9. Batch Processing

The batch API accepts multiple transaction requests.

Each item is validated and processed independently using a separate transaction boundary. A failure in one item should not roll back successfully processed items in the same batch.

Batch items are processed sequentially to preserve account sequence ordering.

## 10. Database Model

### Account

Stores account information:

- `account_id`
- `balance`
- `last_sequence`
- `version`

### Transaction

Stores transaction information:

- `transaction_id`
- `request_id`
- `account_id`
- `transaction_type`
- `amount`
- `currency`
- `sequence_number`
- `status`
- `retry_count`
- `failure_reason`
- `original_transaction_id`
- `created_at`
- `updated_at`

### Processing Attempt

Stores retry and processing history:

- Attempt ID
- Transaction reference
- Attempt number
- Status
- Error message
- Created timestamp

## 11. Running the Application

### Prerequisites

- Java 17
- Maven

### Start the application

From the project root:

```bash
mvn clean install
mvn spring-boot:run
```

The application runs on port `8086`, unless configured otherwise.

### Run automated tests

```bash
mvn clean test
```

## 12. Monitoring

Spring Boot Actuator provides health and application monitoring endpoints.

Examples:

```text
GET /actuator/health
GET /actuator/info
GET /actuator/metrics
```

Custom transaction metrics include counters for received, processed, failed, duplicate, retried, and pending transactions, where configured.

## 13. Testing Scenarios

The automated tests cover the following scenarios:

- Successful CREDIT processing.
- Duplicate transaction submission.
- Out-of-order transaction arrival.
- Insufficient balance during DEBIT.
- Transient failure and retry.
- Maximum retry attempts exhausted.
- Invalid transaction input.
- Concurrent duplicate submissions.
- Successful reversal of CREDIT.
- Successful reversal of DEBIT.
- Reversal when the original transaction does not exist.

Run all tests using:

```bash
mvn clean test
```

## 14. Production Hardening Considerations

Before deploying in a production financial environment, consider:

- Replace H2 with a production database such as Oracle or PostgreSQL.
- Use database migrations such as Liquibase or Flyway.
- Add authentication and authorization.
- Protect sensitive information in logs.
- Use durable retry scheduling and configurable exponential backoff.
- Add distributed locking or equivalent coordination for multiple application instances.
- Ensure reversal uniqueness is protected against concurrent requests.
- Publish external events only after the database transaction commits, using an outbox pattern where appropriate.
- Add dashboards, alerts, tracing, and operational recovery procedures.
- Configure database backups, retention, and disaster recovery.

## 15. Design Summary

The service uses database-backed idempotency, account-level locking, sequence-based ordering, transactional processing, and retry tracking to provide reliable transaction processing.

The implementation is designed as a single Spring Boot application with clear separation between API, business logic, persistence, and scheduled retry responsibilities.
