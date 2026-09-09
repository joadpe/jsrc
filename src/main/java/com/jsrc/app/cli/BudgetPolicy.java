package com.jsrc.app.cli;

import java.util.Map;
import java.util.Set;

/**
 * Policy matrix for command availability and behavior under different budget profiles.
 * Defines which commands are allowed, degraded, or denied per profile.
 */
public class BudgetPolicy {

    public enum Action {
        ALLOW,      // Command runs normally
        DEGRADE,    // Command runs with modifications (e.g., summary→mini)
        DENY        // Command exits with error
    }

    private static final Map<String, Map<BudgetProfile, Action>> COMMAND_POLICY = Map.ofEntries(
        // Navigation - always allowed
        Map.entry("index", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("overview", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("mini", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("validate", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("type-check", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("describe", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("skill", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("doctor", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),

        // List commands - allow with limits
        Map.entry("classes", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("search", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("find", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("scope", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("callers", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("callees", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("related", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("deps", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("hierarchy", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),

        // Summary - degrades under tiny
        Map.entry("summary", Map.of(BudgetProfile.TINY, Action.DEGRADE, BudgetProfile.SMALL, Action.ALLOW)),

        // Read - degrades under tiny for class reads, allows method reads
        Map.entry("read", Map.of(BudgetProfile.TINY, Action.DEGRADE, BudgetProfile.SMALL, Action.DEGRADE)),

        // Analysis - scoped versions allowed
        Map.entry("impact", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("checklist", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),
        Map.entry("smells", Map.of(BudgetProfile.TINY, Action.ALLOW, BudgetProfile.SMALL, Action.ALLOW)),

        // Heavy commands - denied under tiny
        Map.entry("context", Map.of(BudgetProfile.TINY, Action.DENY, BudgetProfile.SMALL, Action.DENY)),
        Map.entry("call-chain", Map.of(BudgetProfile.TINY, Action.DENY, BudgetProfile.SMALL, Action.DENY)),
        Map.entry("dump", Map.of(BudgetProfile.TINY, Action.DENY, BudgetProfile.SMALL, Action.DENY)),
        Map.entry("tour", Map.of(BudgetProfile.TINY, Action.DENY, BudgetProfile.SMALL, Action.DENY)),
        Map.entry("map", Map.of(BudgetProfile.TINY, Action.DENY, BudgetProfile.SMALL, Action.DENY))
    );

    /**
     * Core commands visible under TINY budget (for describe filtering).
     */
    private static final Set<String> TINY_CORE_COMMANDS = Set.of(
        "index", "overview", "mini", "read", "scope", "callers", 
        "validate", "describe", "skill", "classes"
    );

    /**
     * Core commands visible under SMALL budget (for describe filtering).
     */
    private static final Set<String> SMALL_CORE_COMMANDS = Set.of(
        "index", "overview", "mini", "summary", "read", "hierarchy", "deps",
        "callers", "callees", "related", "scope", "classes", "search", "find",
        "smells", "lint", "validate", "type-check", "describe", "skill"
    );

    /**
     * Returns the action for a command under the given profile.
     * Returns ALLOW for STANDARD profile (no restrictions).
     */
    public static Action getAction(String commandName, BudgetProfile profile) {
        if (profile == BudgetProfile.STANDARD) {
            return Action.ALLOW;
        }
        Map<BudgetProfile, Action> profileActions = COMMAND_POLICY.get(commandName);
        if (profileActions == null) {
            // Unknown commands default to allow for small, deny for tiny
            return profile == BudgetProfile.TINY ? Action.DENY : Action.ALLOW;
        }
        return profileActions.getOrDefault(profile, Action.ALLOW);
    }

    /**
     * Returns whether a command should be visible in describe output for the given profile.
     */
    public static boolean isVisibleCommand(String commandName, BudgetProfile profile) {
        return switch (profile) {
            case TINY -> TINY_CORE_COMMANDS.contains(commandName);
            case SMALL -> SMALL_CORE_COMMANDS.contains(commandName);
            case STANDARD -> true;
        };
    }

    /**
     * Returns field names to include for a given result type and profile.
     */
    public static Set<String> getFieldsForProfile(String resultType, BudgetProfile profile) {
        if (profile == BudgetProfile.STANDARD) {
            return null; // All fields
        }
        return switch (resultType) {
            case "class" -> profile == BudgetProfile.TINY 
                ? Set.of("name", "packageName", "methodCount")
                : Set.of("name", "packageName", "methodCount", "file", "startLine", "endLine");
            case "method" -> profile == BudgetProfile.TINY
                ? Set.of("name", "signature", "className")
                : Set.of("name", "signature", "className", "file", "startLine", "endLine");
            default -> null;
        };
    }
}
