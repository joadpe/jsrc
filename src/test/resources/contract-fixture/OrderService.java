package com.example.service;

import java.util.List;
import java.util.Optional;

/**
 * Service for managing orders.
 */
public class OrderService {

    private final OrderRepository repository;
    private final NotificationService notifier;

    public OrderService(OrderRepository repository, NotificationService notifier) {
        this.repository = repository;
        this.notifier = notifier;
    }

    public Order createOrder(String customerId, List<LineItem> items) {
        Order order = new Order(customerId, items);
        repository.save(order);
        notifier.sendConfirmation(order);
        return order;
    }

    public Optional<Order> findById(String id) {
        return repository.findById(id);
    }

    public List<Order> findByCustomer(String customerId) {
        return repository.findByCustomer(customerId);
    }

    public void cancelOrder(String id) {
        repository.findById(id).ifPresent(order -> {
            order.cancel();
            repository.save(order);
            notifier.sendCancellation(order);
        });
    }

    public int countActiveOrders() {
        return repository.countByStatus("ACTIVE");
    }
}
