package com.example;

public class OrderDao extends BaseDao {
    public Object findByCustomer(String customerId) {
        return executeQuery("SELECT * FROM orders WHERE customer_id = ?");
    }
}
