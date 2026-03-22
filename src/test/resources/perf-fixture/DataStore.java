package com.example;

import java.util.List;
import java.util.Map;

public class DataStore {

    private final List<Record> allRecords;

    public DataStore(List<Record> allRecords) {
        this.allRecords = allRecords;
    }

    // Linear scan — O(N) per call
    public String findByName(String name) {
        for (Record r : allRecords) {
            if (r.getName().equals(name)) {
                return r.getId();
            }
        }
        return null;
    }

    public void save(Map<String, Object> data) {
        // persist
    }

    public void writeToFile(java.io.File file, Record r) {
        // writes to disk
    }
}
