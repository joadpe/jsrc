package com.jsrc.app.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.DocCommand;

@Command(name = "doc", description = "Generate Javadoc drafts for undocumented methods")
public class DocSubcommand extends JsrcSubcommand {

    @Parameters(index = "0", paramLabel = "<className>", description = "Class to generate docs for")
    String className;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new DocCommand(className);
    }
}
