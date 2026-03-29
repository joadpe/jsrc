package com.jsrc.app.command.quality;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;

import java.nio.file.Path;
import java.util.*;

import com.jsrc.app.analysis.SourceResolver;
import com.jsrc.app.model.CommandHint;
import com.jsrc.app.util.GitHelper;
import com.jsrc.app.parser.model.ClassInfo;

/**
 * Extracts TODO/FIXME/HACK/XXX comments with context.
 * Integrates with git blame for author and date.
 */
public class TodoCommand implements Command {

    private static final List<String> MARKERS = List.of("TODO", "FIXME", "HACK", "XXX", "NOSONAR", "WORKAROUND");

    @Override
    public int execute(CommandContext ctx) {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Integer> byType = new LinkedHashMap<>();
        Path workdir = Path.of(ctx.rootPath());

        for (Path file : ctx.javaFiles()) {
            try {
                String source = java.nio.file.Files.readString(file);
                String[] lines = source.split("\n");
                String relativePath = workdir.relativize(file).toString();

                // Find containing class
                String className = file.getFileName().toString().replace(".java", "");

                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    int lineNum = i + 1;

                    for (String marker : MARKERS) {
                        int idx = line.indexOf(marker);
                        if (idx >= 0 && (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*"))) {
                            // Extract text after marker
                            String text = line.substring(idx + marker.length()).trim();
                            if (text.startsWith(":")) text = text.substring(1).trim();
                            if (text.startsWith("-")) text = text.substring(1).trim();

                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("type", marker);
                            item.put("text", text);
                            item.put("file", relativePath);
                            item.put("line", lineNum);
                            item.put("class", className);

                            // Find enclosing method (simple heuristic: last method declaration above)
                            String method = findEnclosingMethod(lines, i);
                            if (method != null) item.put("method", method);

                            // Git blame for author + date
                            var blame = GitHelper.blame(workdir, relativePath, lineNum);
                            if (!blame.isEmpty()) {
                                item.put("author", blame.getOrDefault("author", "unknown"));
                                item.put("date", blame.getOrDefault("date", "unknown"));
                                // Calculate age
                                try {
                                    var date = java.time.LocalDate.parse(blame.get("date"));
                                    long days = java.time.temporal.ChronoUnit.DAYS.between(date, java.time.LocalDate.now());
                                    if (days > 365) item.put("age", (days / 365) + " years");
                                    else if (days > 30) item.put("age", (days / 30) + " months");
                                    else item.put("age", days + " days");
                                } catch (Exception e) { /* skip age */ }
                            }

                            items.add(item);
                            byType.merge(marker, 1, Integer::sum);
                            break; // one marker per line
                        }
                    }
                }
            } catch (Exception e) {
                // skip unreadable files
            }
        }

        // Sort: FIXME first, then by age (oldest first)
        items.sort((a, b) -> {
            int typePri = markerPriority(a.get("type").toString()) - markerPriority(b.get("type").toString());
            if (typePri != 0) return typePri;
            return 0; // keep file order within same type
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", items.size());
        result.put("byType", byType);
        if (items.size() > 50) {
            result.put("items", items.subList(0, 50));
            result.put("truncated", true);
        } else {
            result.put("items", items);
        }

        var hints = java.util.List.of(
            new CommandHint("read CLASS.METHOD", "Read the method with TODO")
        );

        ctx.formatter().printResultWithHints(result, hints);
        return items.size();
    }

    private static int markerPriority(String marker) {
        return switch (marker) {
            case "FIXME" -> 0;
            case "HACK" -> 1;
            case "XXX" -> 2;
            case "TODO" -> 3;
            case "WORKAROUND" -> 4;
            case "NOSONAR" -> 5;
            default -> 6;
        };
    }

    private static String findEnclosingMethod(String[] lines, int lineIndex) {
        for (int i = lineIndex - 1; i >= 0; i--) {
            String line = lines[i].trim();
            var m = java.util.regex.Pattern.compile(
                    "(?:public|private|protected|static|void|\\w+)\\s+(\\w+)\\s*\\(").matcher(line);
            if (m.find() && !line.contains("class ") && !line.contains("interface ")) {
                return m.group(1);
            }
        }
        return null;
    }
}
