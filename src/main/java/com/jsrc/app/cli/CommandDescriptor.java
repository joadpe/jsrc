package com.jsrc.app.cli;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CommandDescriptor(
        String name,
        List<String> aliases,
        String summary,
        CommandCategory category,
        List<String> arguments,
        List<String> options,
        CommandOutputType outputType,
        CommandCost cost,
        Map<BudgetProfile, BudgetRule> budgets,
        List<String> examples) {

    public CommandDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(outputType, "outputType");
        Objects.requireNonNull(cost, "cost");
        aliases = List.copyOf(aliases);
        arguments = List.copyOf(arguments);
        options = List.copyOf(options);
        budgets = Map.copyOf(budgets);
        examples = List.copyOf(examples);
    }

    public BudgetRule budgetRule(BudgetProfile profile) {
        return Objects.requireNonNull(budgets.get(profile),
                () -> "Missing budget rule for " + name + " under " + profile);
    }
}
