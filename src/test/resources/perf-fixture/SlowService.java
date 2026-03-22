package com.example;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class SlowService {

    private final List<Record> records;
    private final DataStore store;

    public SlowService(List<Record> records, DataStore store) {
        this.records = records;
        this.store = store;
    }

    // O(N²): loop calls linear scan
    public List<String> processAll() {
        List<String> results = new ArrayList<>();
        for (Record r : records) {
            String name = store.findByName(r.getName());
            results.add(name);
        }
        return results;
    }

    // Allocation in loop
    public void batchUpdate() {
        for (Record r : records) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", r.getId());
            map.put("name", r.getName());
            store.save(map);
        }
    }

    // I/O in loop
    public void exportAll() {
        for (Record r : records) {
            java.io.File f = new java.io.File(r.getName() + ".txt");
            store.writeToFile(f, r);
        }
    }
}
