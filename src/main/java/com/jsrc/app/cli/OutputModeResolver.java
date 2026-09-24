package com.jsrc.app.cli;

import com.jsrc.app.config.ProjectConfig;
import java.nio.file.Path;

final class OutputModeResolver {

    private OutputModeResolver() {}

    static BudgetProfile resolveBudgetProfile(String cliBudget, String configPath) {
        if (cliBudget != null) {
            return BudgetProfile.fromString(cliBudget);
        }
        String envBudget = System.getenv("JSRC_BUDGET");
        if (envBudget != null && !envBudget.isBlank()) {
            return BudgetProfile.fromString(envBudget);
        }
        ProjectConfig config = configPath != null
                ? ProjectConfig.loadFrom(Path.of(configPath)).orElse(null)
                : ProjectConfig.load(Path.of(".")).orElse(null);
        if (config != null && config.budget() != null) {
            return BudgetProfile.fromString(config.budget());
        }
        return BudgetProfile.STANDARD;
    }

    static boolean effectiveJson(boolean json, boolean markdown, BudgetProfile profile) {
        return json || (profile.forceJson() && !markdown);
    }
}
