package com.jsrc.app.cli;

import com.jsrc.app.config.ProjectConfig;
import java.util.function.Supplier;

final class OutputModeResolver {

    private OutputModeResolver() {}

    /**
     * Resolves {@code --budget}, then {@code JSRC_BUDGET}, then the {@code budget} of the
     * command's project config, which is loaded only when neither of the others is set.
     */
    static BudgetProfile resolveBudgetProfile(
            String cliBudget, Supplier<ProjectConfig> projectConfig) {
        if (cliBudget != null) {
            return BudgetProfile.fromString(cliBudget);
        }
        String envBudget = System.getenv("JSRC_BUDGET");
        if (envBudget != null && !envBudget.isBlank()) {
            return BudgetProfile.fromString(envBudget);
        }
        ProjectConfig config = projectConfig.get();
        if (config != null && config.budget() != null) {
            return BudgetProfile.fromString(config.budget());
        }
        return BudgetProfile.STANDARD;
    }

    static boolean effectiveJson(boolean json, boolean markdown, BudgetProfile profile) {
        return json || (profile.forceJson() && !markdown);
    }
}
