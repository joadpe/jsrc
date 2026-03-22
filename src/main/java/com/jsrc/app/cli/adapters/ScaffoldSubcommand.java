package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.quality.ScaffoldCommand;

@Command(name = "scaffold", description = "Generate code following project conventions")
public class ScaffoldSubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<pattern>", description = "Pattern: service|controller|dao|dto|entity|test")
    String pattern;

    @Parameters(index = "1", paramLabel = "<name>", description = "Class name to generate")
    String name;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new ScaffoldCommand(pattern, name);
    }
}
