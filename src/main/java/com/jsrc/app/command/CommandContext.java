package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.jsrc.app.config.ProjectConfig;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.CodeParser;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Shared context for all commands. Created once, passed to each command.
 */
public record CommandContext(
        List<Path> javaFiles,
        String rootPath,
        ProjectConfig config,
        OutputFormatter formatter,
        IndexedCodebase indexed,
        CodeParser parser
) {
    /**
     * Returns all classes, using index if available, parsing on-the-fly otherwise.
     */
    public List<ClassInfo> getAllClasses() {
        if (indexed != null) return indexed.getAllClasses();
        List<ClassInfo> all = new ArrayList<>();
        for (Path file : javaFiles) all.addAll(parser.parseClasses(file));
        return all;
    }
}
