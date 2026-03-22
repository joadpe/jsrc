package com.jsrc.app.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.HierarchyCommand;

@Command(name = "hierarchy", description = "Inheritance tree: extends, implements, subclasses")
public class HierarchySubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<className>", description = "Class to inspect")
    String className;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new HierarchyCommand(className);
    }
}
