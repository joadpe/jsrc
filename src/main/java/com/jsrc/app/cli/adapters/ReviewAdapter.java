package com.jsrc.app.cli.adapters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.reverse.ReviewCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "review", description = "Offline review of local Git changes: symbols, contracts, impact and tests")
public final class ReviewAdapter extends PicocliAdapter {
    @Parameters(index = "0", arity = "0..1", paramLabel = "<ref>",
            description = "Commit to compare against (default: HEAD)")
    String ref;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new ReviewCommand(ref);
    }
}
