package com.example.controller;

import java.util.List;

/**
 * REST controller for orders.
 */
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    public Order getOrder(String id) {
        return orderService.findById(id).orElse(null);
    }

    public List<Order> listOrders(String customerId) {
        return orderService.findByCustomer(customerId);
    }

    public Order createOrder(String customerId, List<LineItem> items) {
        return orderService.createOrder(customerId, items);
    }

    public void deleteOrder(String id) {
        orderService.cancelOrder(id);
    }
}
