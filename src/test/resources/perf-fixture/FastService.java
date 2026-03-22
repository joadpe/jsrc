package com.example;

import java.util.Map;
import java.util.HashMap;

public class FastService {

    private final Map<String, String> cache;

    public FastService(Map<String, String> cache) {
        this.cache = cache;
    }

    // O(1) lookup — no issues
    public String findById(String id) {
        return cache.get(id);
    }

    // Simple processing — no issues
    public int count() {
        return cache.size();
    }
}
