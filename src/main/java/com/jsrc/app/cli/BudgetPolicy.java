package com.jsrc.app.cli;

import java.util.LinkedHashSet;
import java.util.Set;

/** Compatibility facade over budget metadata in the canonical command catalog. */
public final class BudgetPolicy {

    public enum Action {
        ALLOW,
        DEGRADE,
        DENY
    }

    private BudgetPolicy() {}

    public static Action getAction(String commandName, BudgetProfile profile) {
        return DefaultCommandRegistry.create().budgetRule(commandName, profile).action();
    }

    public static Set<String> budgetSurface(BudgetProfile profile) {
        return DefaultCommandRegistry.create().visibleCommands(profile).stream()
                .map(CommandDescriptor::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public static boolean isVisibleCommand(String commandName, BudgetProfile profile) {
        return DefaultCommandRegistry.create().find(commandName)
                .map(command -> command.budgetRule(profile).visible())
                .orElse(false);
    }

    public static Set<String> getFieldsForProfile(String resultType, BudgetProfile profile) {
        if (profile == BudgetProfile.STANDARD) {
            return null;
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
