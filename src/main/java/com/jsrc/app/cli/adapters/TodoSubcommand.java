package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.quality.TodoCommand;

@Command(name = "todo", description = "Extract TODO/FIXME/HACK/XXX with git blame context")
public class TodoSubcommand extends JsrcSubcommand {

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new TodoCommand();
    }
}
