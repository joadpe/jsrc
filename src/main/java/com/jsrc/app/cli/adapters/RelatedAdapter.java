package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.navigate.RelatedCommand;

@Command(name = "related", description = "Related classes by coupling (shared imports/callers)")
public class RelatedAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "<className>", description = "Class to find relations for")
    String className;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new RelatedCommand(className);
    }
}
