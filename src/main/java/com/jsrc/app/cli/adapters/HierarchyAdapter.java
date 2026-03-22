package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.navigate.HierarchyCommand;

@Command(name = "hierarchy", description = "Inheritance tree: extends, implements, subclasses")
public class HierarchyAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "<className>", description = "Class to inspect")
    String className;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new HierarchyCommand(className);
    }
}
