package com.example;

import java.util.List;

public class NestedCallService {

    private final List<Record> items;

    public NestedCallService(List<Record> items) {
        this.items = items;
    }

    // Loop calls same-class method that does linear scan
    public void processAll() {
        for (Record item : items) {
            String result = lookupName(item.getId());
            System.out.println(result);
        }
    }

    // Linear scan — O(N) per call
    private String lookupName(String id) {
        for (Record r : items) {
            if (r.getId().equals(id)) {
                return r.getName();
            }
        }
        return null;
    }
}
