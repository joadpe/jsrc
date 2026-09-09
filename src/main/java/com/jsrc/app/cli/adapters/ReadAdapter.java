package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.navigate.ReadCommand;

@Command(name = "read", description = "Source code of a class or method")
public class ReadAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "<target>",
                description = "Class or Class.method to read")
    String target;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        // Budget degradation for read: deny whole-class reads under TINY
        BudgetContext budgetCtx = parent.buildBudgetContext();
        BudgetProfile profile = budgetCtx.profile();
        
        // If target is a class without method (no dot, no parens)
        boolean isWholeClassRead = !target.contains(".") && !target.contains("(");
        
        if (isWholeClassRead && profile == BudgetProfile.TINY) {
            // Deny whole class reads under TINY - suggest reading specific method
            budgetCtx.setDegradedFrom("read");
            // Return a command that will deny with structured error
            return new com.jsrc.app.command.BudgetDeniedCommand(
                "read", 
                profile, 
                "jsrc read " + target + ".METHOD --json (see jsrc mini " + target + " for method list)"
            );
        }
        
        return new ReadCommand(target);
    }
}
