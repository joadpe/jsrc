package com.example;

public abstract class BaseDao {
    protected Object executeQuery(String sql) {
        // uses PreparedStatement internally
        return null;
    }
}
