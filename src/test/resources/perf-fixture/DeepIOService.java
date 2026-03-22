package com.example;

import java.util.List;

public class DeepIOService {

    private final List<Record> items;
    private final FileProcessor processor;

    public DeepIOService(List<Record> items, FileProcessor processor) {
        this.items = items;
        this.processor = processor;
    }

    // Loop calls processItem which calls writeResult which does I/O
    public void processAll() {
        for (Record item : items) {
            processItem(item);
        }
    }

    // No direct I/O — delegates
    private void processItem(Record item) {
        String transformed = transform(item.getName());
        writeResult(transformed);
    }

    // No direct I/O — delegates
    private String transform(String input) {
        return input.toUpperCase();
    }

    // This is where I/O actually happens
    private void writeResult(String data) {
        java.io.File output = new java.io.File("output.txt");
        // writes to disk
    }
}
