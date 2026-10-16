package com.jsrc.app.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;
import com.jsrc.app.util.SourceText;

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
        String simpleName = declaredSimpleName(className);
        for (Path file : files) {
            if (simpleName != null && cannotDeclare(file, simpleName)) continue;
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
        return com.jsrc.app.model.TypeId.namesMatch(actual, expected)
                || com.jsrc.app.model.TypeId.namesMatch(expected, actual);
    }

    /**
     * Returns the simple name that every class matching {@code className} declares, or null
     * when it cannot be told from the text. Matching names share their last '.' or '$'
     * segment, and a declared type spells that segment in its source, except for local
     * types, whose binary names end in an ordinal.
     */
    static String declaredSimpleName(String className) {
        String name = className.substring(
                Math.max(className.lastIndexOf('.'), className.lastIndexOf('$')) + 1);
        if (name.isEmpty() || !isAsciiLetterOrUnderscore(name.charAt(0))) return null;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!isAsciiLetterOrUnderscore(c) && (c < '0' || c > '9')) return null;
        }
        return name;
    }

    private static boolean isAsciiLetterOrUnderscore(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * True when the file cannot declare a type named {@code simpleName}, so parsing it is
     * pointless. The bytes are compared directly because an ASCII name has the same bytes
     * in UTF-8 and in single-byte encodings; unicode escapes could spell the name otherwise.
     */
    private static boolean cannotDeclare(Path file, String simpleName) {
        try {
            String text = new String(java.nio.file.Files.readAllBytes(file),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            return !text.contains("\\u") && !text.contains(simpleName);
        } catch (IOException e) {
            return false; // Let the parser read it and report the failure.
        }
    }

    private String extractLines(Path file, int startLine, int endLine) {
        try {
            List<String> lines = SourceText.readLines(file);
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
