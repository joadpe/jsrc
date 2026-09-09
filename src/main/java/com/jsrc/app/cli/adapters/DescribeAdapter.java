package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.meta.DescribeCommand;

@Command(name = "describe", description = "List available commands (budget-aware)")
public class DescribeAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "[command]", description = "Specific command to describe", arity = "0..1")
    String commandName;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new DescribeCommand(parent.resolveBudgetProfile(), commandName);
    }
}
