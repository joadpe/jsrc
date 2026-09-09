package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.BudgetContext;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.navigate.SummaryCommand;

@Command(name = "summary", description = "Class metadata + method signatures")
public class SummaryAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "<className>", description = "Class to summarize")
    String className;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        // Budget degradation: summary → mini under TINY profile
        BudgetContext budgetCtx = parent.buildBudgetContext();
        BudgetProfile profile = budgetCtx.profile();
        
        if (profile == BudgetProfile.TINY) {
            budgetCtx.setDegradedFrom("summary");
            return new com.jsrc.app.command.navigate.MiniCommand(className);
        }
        
        return new SummaryCommand(className);
    }
}
