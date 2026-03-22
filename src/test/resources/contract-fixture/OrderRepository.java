package com.example.repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Order persistence.
 */
public interface OrderRepository {

    void save(Order order);

    Optional<Order> findById(String id);

    List<Order> findByCustomer(String customerId);

    int countByStatus(String status);

    void delete(String id);
}
