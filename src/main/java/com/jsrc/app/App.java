package com.jsrc.app;

import com.jsrc.app.cli.JsrcCommand;
import picocli.CommandLine;

/**
 * CLI entry point for jsrc — Java source code analysis tool.
 * Delegates to picocli {@link JsrcCommand} for argument parsing and dispatch.
 */
public class App {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JsrcCommand()).execute(args);
        System.exit(exitCode);
    }
}
