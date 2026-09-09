package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.meta.SkillCommand;

@Command(name = "skill", description = "Compact skill guide for agents (budget-aware)")
public class SkillAdapter extends PicocliAdapter {

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new SkillCommand(parent.resolveBudgetProfile());
    }
}
