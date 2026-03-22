package com.example.model;

/**
 * A line item in an order.
 */
public record LineItem(String productId, int quantity, double price) {

    public double getSubtotal() {
        return quantity * price;
    }
}
