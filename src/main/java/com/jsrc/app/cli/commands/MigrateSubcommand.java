package com.jsrc.app.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.quality.MigrateCommand;

@Command(name = "migrate", description = "Detect Java modernization opportunities (Java 8→17/21)")
public class MigrateSubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<className>", description = "Class to analyze", defaultValue = "")
    String className;

    @Option(names = "--target", description = "Target Java version (default: 17)", defaultValue = "17")
    int targetVersion;

    @Option(names = "--all", description = "Scan entire codebase")
    boolean all;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new MigrateCommand(className.isEmpty() ? null : className, targetVersion, all);
    }
}
