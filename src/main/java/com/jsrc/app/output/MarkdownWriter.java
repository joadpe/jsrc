package com.jsrc.app.output;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Writes Markdown output to stdout or to a file in --out directory.
 */
public final class MarkdownWriter {

    private MarkdownWriter() {}

    /**
     * Outputs markdown to stdout or writes to a file.
     *
     * @param markdown the markdown content
     * @param outDir   output directory (null = stdout)
     * @param filename filename without extension (e.g. "smells-report")
     */
    public static void output(String markdown, String outDir, String filename) {
        if (outDir == null) {
            System.out.println(markdown);
            return;
        }
        try {
            Path dir = Paths.get(outDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(filename + ".md");
            Files.writeString(file, markdown);
            System.err.printf("Written: %s%n", file);
        } catch (IOException e) {
            System.err.printf("Error writing %s: %s%n", filename, e.getMessage());
            // Fallback to stdout
            System.out.println(markdown);
        }
    }
}
