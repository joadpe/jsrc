package com.jsrc.app.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class CommandDocumentationRenderer {

    public static final String BEGIN_MARKER = "<!-- BEGIN GENERATED COMMAND CATALOG -->";
    public static final String END_MARKER = "<!-- END GENERATED COMMAND CATALOG -->";

    private final CommandCatalog catalog;

    public CommandDocumentationRenderer(CommandCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public String renderCatalog() {
        StringBuilder markdown = new StringBuilder(BEGIN_MARKER).append('\n');
        markdown.append("| Command | Category | Summary |\n");
        markdown.append("|---|---|---|\n");
        for (CommandDescriptor command : catalog.commands()) {
            markdown.append("| `")
                    .append(command.name())
                    .append("` | ")
                    .append(command.category().name().toLowerCase().replace('_', '-'))
                    .append(" | ")
                    .append(escapeCell(command.summary()))
                    .append(" |\n");
        }
        return markdown.append(END_MARKER).toString();
    }

    public String extractGeneratedBlock(String document) {
        int begin = document.indexOf(BEGIN_MARKER);
        int end = document.indexOf(END_MARKER);
        validateMarkers(begin, end);
        return document.substring(begin, end + END_MARKER.length());
    }

    public String replaceGeneratedBlock(String document) {
        int begin = document.indexOf(BEGIN_MARKER);
        int end = document.indexOf(END_MARKER);
        validateMarkers(begin, end);
        return document.substring(0, begin)
                + renderCatalog()
                + document.substring(end + END_MARKER.length());
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected README and SKILL paths");
        }
        CommandDocumentationRenderer renderer =
                new CommandDocumentationRenderer(DefaultCommandRegistry.create());
        for (String argument : args) {
            Path path = Path.of(argument);
            Files.writeString(path, renderer.replaceGeneratedBlock(Files.readString(path)));
        }
    }

    private static void validateMarkers(int begin, int end) {
        if (begin < 0 || end < 0 || end < begin) {
            throw new IllegalArgumentException("Generated command catalog markers are missing or invalid");
        }
    }

    private static String escapeCell(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }
}
