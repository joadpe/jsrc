package com.jsrc.app.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.ReadCommand;

@Command(name = "read", description = "Source code of a class or method")
public class ReadSubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<target>",
                description = "Class or Class.method to read")
    String target;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new ReadCommand(target);
    }
}
