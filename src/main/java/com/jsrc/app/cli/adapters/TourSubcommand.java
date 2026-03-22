package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;

import com.jsrc.app.cli.JsrcSubcommand;
import com.jsrc.app.command.quality.TourCommand;

@Command(name = "tour", description = "Guided tour of the codebase for onboarding")
public class TourSubcommand extends JsrcSubcommand {
    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new TourCommand();
    }
}
