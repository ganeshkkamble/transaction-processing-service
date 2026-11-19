
package com.fde.transaction.service;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class TransactionMetrics {

    private final Counter receivedCounter;
    private final Counter processedCounter;
    private final Counter failedCounter;
    private final Counter duplicateCounter;
    private final Counter retriedCounter;
    private final Counter pendingCounter;

    public TransactionMetrics(MeterRegistry registry) {

        receivedCounter = Counter.builder("transactions.received")
                .description("Total transactions received")
                .register(registry);

        processedCounter = Counter.builder("transactions.processed")
                .description("Total transactions processed successfully")
                .register(registry);

        failedCounter = Counter.builder("transactions.failed")
                .description("Total failed transactions")
                .register(registry);

        duplicateCounter = Counter.builder("transactions.duplicate")
                .description("Total duplicate transactions")
                .register(registry);

        retriedCounter = Counter.builder("transactions.retried")
                .description("Total transaction retry attempts")
                .register(registry);

        pendingCounter = Counter.builder("transactions.pending")
                .description("Total transactions placed in pending status")
                .register(registry);
    }

    public void incrementReceived() {
        receivedCounter.increment();
    }

    public void incrementProcessed() {
        processedCounter.increment();
    }

    public void incrementFailed() {
        failedCounter.increment();
    }

    public void incrementDuplicate() {
        duplicateCounter.increment();
    }

    public void incrementRetried() {
        retriedCounter.increment();
    }

    public void incrementPending() {
        pendingCounter.increment();
    }
}
