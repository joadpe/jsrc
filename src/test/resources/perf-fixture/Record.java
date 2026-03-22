package com.example;

public record Record(String id, String name) {
    public String getId() { return id; }
    public String getName() { return name; }
}
