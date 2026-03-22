package com.example.model;

import java.util.List;

/**
 * Order entity.
 */
public class Order {

    private final String id;
    private final String customerId;
    private final List<LineItem> items;
    private String status;

    public Order(String customerId, List<LineItem> items) {
        this.id = java.util.UUID.randomUUID().toString();
        this.customerId = customerId;
        this.items = items;
        this.status = "ACTIVE";
    }

    public String getId() { return id; }
    public String getCustomerId() { return customerId; }
    public List<LineItem> getItems() { return items; }
    public String getStatus() { return status; }

    public void cancel() {
        this.status = "CANCELLED";
    }

    public double getTotal() {
        return items.stream().mapToDouble(LineItem::getSubtotal).sum();
    }
}
