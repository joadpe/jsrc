package com.jsrc.app.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.ImplementsCommand;

@Command(name = "implements", description = "Find all implementors of an interface")
public class ImplementsSubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<interfaceName>", description = "Interface to find implementors of")
    String interfaceName;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new ImplementsCommand(interfaceName);
    }
}
