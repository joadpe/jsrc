package com.jsrc.app.cli.adapters;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import com.jsrc.app.cli.PicocliAdapter;
import com.jsrc.app.command.navigate.AnnotationsCommand;

@Command(name = "annotations", description = "Find all elements with a specific annotation")
public class AnnotationsAdapter extends PicocliAdapter {

    @Parameters(index = "0", paramLabel = "<annotationName>", description = "Annotation to search for")
    String annotationName;

    @Override
    protected com.jsrc.app.command.Command createCommand() {
        return new AnnotationsCommand(annotationName);
    }
}
