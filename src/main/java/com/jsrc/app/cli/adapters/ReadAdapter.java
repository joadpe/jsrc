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
        return new ReadCommand(target);
    }
}
