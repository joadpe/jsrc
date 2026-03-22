package com.example.model;

/**
 * Base class for entities with an ID.
 */
public abstract class AbstractEntity {

    protected String id;

    public String getId() { return id; }

    public abstract String getDisplayName();
}
