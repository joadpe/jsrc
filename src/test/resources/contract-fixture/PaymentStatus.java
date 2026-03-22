package com.example.model;

/**
 * Payment status enum.
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    REFUNDED,
    FAILED;

    public boolean isTerminal() {
        return this == REFUNDED || this == FAILED;
    }
}
