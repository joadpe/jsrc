package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.navigate.ImplementsCommand;

@Command(name = "implements", description = "Find all implementors of an interface")
public class ImplementsAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "<interfaceName>", description = "Interface to find implementors of")
    String interfaceName;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new ImplementsCommand(interfaceName);
    }
}
