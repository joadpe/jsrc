package com.jsrc.app.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

/**
 * Reads source code for specific classes or methods.
 * Returns the actual source text along with metadata.
 */
public class SourceReader {

    private static final Logger logger = LoggerFactory.getLogger(SourceReader.class);

    private final CodeParser parser;

    public SourceReader(CodeParser parser) {
        this.parser = parser;
    }

    /**
     * Result of a source read operation.
     */
    public record ReadResult(
            String className,
            String methodName,
            Path file,
            int startLine,
            int endLine,
            String content
    ) {}

    /**
     * Reads the source code of a specific method.
     * <p>
     * Note: className should be a simple name (e.g. "Service"), not qualified.
     * For qualified names like "com.app.Service", use --summary instead.
     * The dot in "com.app.Service" is ambiguous with "Class.method" format.
     *
     * @param files     Java files to search
     * @param className class name (simple name recommended)
     * @param methodName method name
     * @return source with metadata, or empty if not found
     */
    public Optional<ReadResult> readMethod(List<Path> files, String className, String methodName) {
        for (Path file : files) {
            List<MethodInfo> methods = parser.findMethods(file, methodName);
            for (MethodInfo m : methods) {
                if (matchesClass(m.className(), className)) {
                    return Optional.of(new ReadResult(
                            m.className(), m.name(), file,
                            m.startLine(), m.endLine(), m.content()));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Reads the full source code of a class.
     *
     * @param files     Java files to search
     * @param className class name (simple or qualified)
     * @return source with metadata, or empty if not found
     */
    public Optional<ReadResult> readClass(List<Path> files, String className) {
        for (Path file : files) {
            List<ClassInfo> classes = parser.parseClasses(file);
            for (ClassInfo ci : classes) {
                if (matchesClass(ci.name(), className)
                        || matchesClass(ci.qualifiedName(), className)) {
                    String content = extractLines(file, ci.startLine(), ci.endLine());
                    if (content != null) {
                        return Optional.of(new ReadResult(
                                ci.name(), null, file,
                                ci.startLine(), ci.endLine(), content));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean matchesClass(String actual, String expected) {
        return actual.equals(expected)
                || actual.endsWith("." + expected)
                || expected.endsWith("." + actual);
    }

    private String extractLines(Path file, int startLine, int endLine) {
        try {
            List<String> lines = Files.readAllLines(file);
            int start = Math.max(0, startLine - 1);
            int end = Math.min(lines.size(), endLine);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(lines.get(i)).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            logger.error("Error reading {}: {}", file, e.getMessage());
            return null;
        }
    }
}
