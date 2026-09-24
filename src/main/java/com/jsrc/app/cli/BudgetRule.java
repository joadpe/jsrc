package com.jsrc.app.cli;

import java.util.Optional;

public record BudgetRule(
        BudgetPolicy.Action action,
        boolean visible,
        Optional<String> alternative,
        Optional<String> reason) {

    public BudgetRule {
        alternative = alternative == null ? Optional.empty() : alternative;
        reason = reason == null ? Optional.empty() : reason;
    }

    static BudgetRule of(BudgetPolicy.Action action, boolean visible) {
        return new BudgetRule(action, visible, Optional.empty(), Optional.empty());
    }
}
