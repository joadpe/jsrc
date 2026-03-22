package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.quality.SecurityCommand;

@Command(name = "security", description = "Static security analysis — SQL injection, path traversal, XXE, secrets")
public class SecurityAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "<className>", description = "Class to scan", defaultValue = "")
    String className;

    @Option(names = "--all", description = "Scan entire codebase")
    boolean all;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new SecurityCommand(className.isEmpty() ? null : className, all);
    }
}
