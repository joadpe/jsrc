package com.jsrc.app.cli;

/**
 * Budget profile for controlling output size and command surface for small/local agents.
 * Profiles enforce hard limits on token usage and command complexity.
 */
public enum BudgetProfile {
    /**
     * Tiny profile (~4K context window).
     * Minimal output, core commands only, aggressive degradations.
     */
    TINY("tiny", 10, 2048),

    /**
     * Small profile (~8K context window).
     * Moderate output limits, most commands available with degradations.
     */
    SMALL("small", 30, 8192),

    /**
     * Standard profile (no artificial limits).
     * Full output and command surface. Default behavior.
     */
    STANDARD("standard", Integer.MAX_VALUE, Integer.MAX_VALUE);

    private final String name;
    private final int defaultLimit;
    private final int defaultMaxBytes;

    BudgetProfile(String name, int defaultLimit, int defaultMaxBytes) {
        this.name = name;
        this.defaultLimit = defaultLimit;
        this.defaultMaxBytes = defaultMaxBytes;
    }

    public String profileName() {
        return name;
    }

    public int defaultLimit() {
        return defaultLimit;
    }

    public int defaultMaxBytes() {
        return defaultMaxBytes;
    }

    public static BudgetProfile fromString(String name) {
        if (name == null || name.isBlank()) {
            return STANDARD;
        }
        String normalized = name.trim().toLowerCase();
        return switch (normalized) {
            case "tiny" -> TINY;
            case "small" -> SMALL;
            case "standard" -> STANDARD;
            default -> throw new IllegalArgumentException(
                "Invalid budget profile: " + name + ". Valid values: tiny, small, standard");
        };
    }

    /**
     * Whether this profile forces JSON output when --md is not set.
     */
    public boolean forceJson() {
        return this != STANDARD;
    }

    /**
     * Field set for this profile.
     */
    public FieldSet fieldSet() {
        return switch (this) {
            case TINY -> FieldSet.TINY;
            case SMALL -> FieldSet.SMALL;
            case STANDARD -> FieldSet.ALL;
        };
    }

    /**
     * Predefined field sets for different budget profiles.
     */
    public enum FieldSet {
        TINY,
        SMALL,
        ALL
    }
}
