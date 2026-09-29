package com.jsrc.app.command.search;

import com.jsrc.app.command.Command;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.command.CommandEngineSource;
import com.jsrc.app.engine.JsrcEngine;
import com.jsrc.app.model.CommandHint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CLI adapter for structured text search. */
public class SearchCommand implements Command {
    private final String pattern;

    public SearchCommand(String pattern) {
        this.pattern = pattern;
    }

    @Override
    public int execute(CommandContext ctx) {
        var matches = new JsrcEngine().search(
                new CommandEngineSource(ctx), pattern).matches();
        List<Map<String, Object>> results = new ArrayList<>(matches.size());
        for (var match : matches) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("file", match.file());
            entry.put("line", match.line());
            entry.put("context", match.context());
            entry.put("class", match.className());
            entry.put("method", match.methodName());
            entry.put("inComment", match.inComment());
            results.add(entry);
        }

        if (!ctx.fullOutput() && results.size() > 30) {
            var byClass = new LinkedHashMap<String, Integer>();
            int inCode = 0;
            int inComments = 0;
            for (var result : results) {
                String className = (String) result.getOrDefault("className", "");
                if (!className.isEmpty()) byClass.merge(className, 1, Integer::sum);
                if (Boolean.TRUE.equals(result.get("inComment"))) inComments++;
                else inCode++;
            }
            var compact = new LinkedHashMap<String, Object>();
            compact.put("total", results.size());
            compact.put("inCode", inCode);
            compact.put("inComments", inComments);
            compact.put("classesMentioned", byClass.size());
            compact.put("topClasses", byClass.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .map(entry -> Map.of("class", entry.getKey(), "count", entry.getValue()))
                    .toList());
            compact.put("matches", results.subList(0, 30));
            compact.put("truncated", true);
            compact.put("hint", "Use --full to see all " + results.size() + " matches");

            String firstClass = (String) results.getFirst().getOrDefault("class", "CLASS");
            var hints = List.of(
                    new CommandHint("read " + firstClass, "Read the matching class"),
                    new CommandHint("find \"keyword\"", "Semantic search instead"));
            ctx.formatter().printResultWithHints(compact, hints);
        } else {
            ctx.formatter().printResult(results);
        }
        return results.size();
    }
}
