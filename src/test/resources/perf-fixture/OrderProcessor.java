package com.example;

import java.util.List;

public class OrderProcessor {

    private final OrderDao orderDao;

    public OrderProcessor(OrderDao orderDao) {
        this.orderDao = orderDao;
    }

    // N+1 query via DAO inheritance — orderDao extends BaseDao
    public void processCustomers(List<String> customerIds) {
        for (String id : customerIds) {
            orderDao.findByCustomer(id);
        }
    }
}
