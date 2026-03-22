package com.jsrc.app.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.jsrc.app.command.CommandContext;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Resolves and loads source code for classes and methods.
 * Centralizes source loading logic used by perf, security, and other analysis commands.
 */
public class SourceResolver {

    /**
     * Loads a class's full source code, using index for fast lookup or file scan as fallback.
     */
    public static String loadClassSource(String className, CommandContext ctx) {
        // Strategy 1: index lookup
        if (ctx.indexed() != null) {
            var filePath = ctx.indexed().findFileForClass(className);
            if (filePath.isPresent()) {
                try {
                    return Files.readString(Path.of(ctx.rootPath()).resolve(filePath.get()));
                } catch (Exception e) { /* fallback */ }
            }
        }
        // Strategy 2: file scan
        for (Path file : ctx.javaFiles()) {
            if (file.getFileName().toString().equals(className + ".java")) {
                try {
                    return Files.readString(file);
                } catch (Exception e) { /* ignore */ }
            }
        }
        return null;
    }

    /**
     * Loads source code of a specific method within a class.
     */
    public static String loadMethodSource(String className, String methodName, CommandContext ctx) {
        String classSource = loadClassSource(className, ctx);
        if (classSource == null) return null;
        return extractMethodByName(classSource, methodName);
    }

    /**
     * Extracts the body of a method declaration from source code.
     * Matches method DECLARATIONS (with modifiers), not method CALLS.
     */
    public static String extractMethodByName(String source, String methodName) {
        String[] lines = source.split("\n");
        int declLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.contains(" " + methodName + "(") || trimmed.contains("\t" + methodName + "(")) {
                if (trimmed.startsWith("public ") || trimmed.startsWith("private ")
                        || trimmed.startsWith("protected ") || trimmed.startsWith("static ")
                        || trimmed.startsWith("void ") || trimmed.startsWith("abstract ")
                        || trimmed.startsWith("final ") || trimmed.startsWith("synchronized ")
                        || trimmed.matches("^\\w[\\w<>\\[\\],\\s]*\\s+" + methodName + "\\s*\\(.*")) {
                    declLine = i;
                    break;
                }
            }
        }
        if (declLine < 0) return null;

        int declIdx = 0;
        for (int i = 0; i < declLine; i++) declIdx += lines[i].length() + 1;
        int braceStart = source.indexOf('{', declIdx);
        if (braceStart < 0) return null;

        int depth = 0;
        int end = braceStart;
        for (int i = braceStart; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            else if (source.charAt(i) == '}') {
                depth--;
                if (depth == 0) { end = i; break; }
            }
        }
        return source.substring(braceStart, end + 1);
    }

    /**
     * Extracts method source lines between startLine and endLine.
     */
    public static String extractMethodSource(String source, int startLine, int endLine) {
        String[] lines = source.split("\n");
        if (startLine < 1 || endLine > lines.length) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = startLine - 1; i < endLine && i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    /**
     * Resolves a field or variable name to its type using ClassInfo fields.
     */
    public static String resolveFieldType(String fieldOrVar, ClassInfo ci) {
        for (var field : ci.fields()) {
            if (field.name().equals(fieldOrVar)) {
                return field.type();
            }
        }
        return fieldOrVar;
    }
}
