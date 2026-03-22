package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.quality.DocCommand;

@Command(name = "doc", description = "Generate Javadoc drafts for undocumented methods")
public class DocAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "<className>", description = "Class to generate docs for")
    String className;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new DocCommand(className);
    }
}
