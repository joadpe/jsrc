package com.jsrc.app.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.CompatCommand;

@Command(name = "compat", description = "Check compatibility for Java version migration")
public class CompatSubcommand extends JsrcSubcommand {

    @Option(names = "--target", description = "Target Java version (default: 17)", defaultValue = "17")
    int targetVersion;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new CompatCommand(targetVersion);
    }
}
