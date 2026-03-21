package com.jsrc.app.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.jsrc.app.model.AnnotationMatch;
import com.jsrc.app.parser.model.AnnotationInfo;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodInfo;

public class AnnotationsCommand implements Command {
    private final String annotationName;

    public AnnotationsCommand(String annotationName) {
        this.annotationName = annotationName;
    }

    @Override
    public int execute(CommandContext ctx) {
        List<AnnotationMatch> matches = new ArrayList<>();

        if (ctx.indexed() != null) {
            for (MethodInfo m : ctx.indexed().findMethodsByAnnotation(annotationName)) {
                AnnotationInfo ann = m.annotations().stream()
                        .filter(a -> a.name().equals(annotationName))
                        .findFirst().orElse(AnnotationInfo.marker(annotationName));
                String filePath = ctx.indexed().findFileForClass(m.className()).orElse("");
                matches.add(new AnnotationMatch("method", m.name(), m.className(),
                        Path.of(filePath), m.startLine(), ann));
            }
            for (ClassInfo ci : ctx.indexed().findClassesByAnnotation(annotationName)) {
                AnnotationInfo ann = ci.annotations().stream()
                        .filter(a -> a.name().equals(annotationName))
                        .findFirst().orElse(AnnotationInfo.marker(annotationName));
                String filePath = ctx.indexed().findFileForClass(ci.name()).orElse("");
                matches.add(new AnnotationMatch("class", ci.name(), ci.name(),
                        Path.of(filePath), ci.startLine(), ann));
            }
        } else {
            for (Path file : ctx.javaFiles()) {
                for (MethodInfo m : ctx.parser().findMethodsByAnnotation(file, annotationName)) {
                    AnnotationInfo ann = m.annotations().stream()
                            .filter(a -> a.name().equals(annotationName))
                            .findFirst().orElse(AnnotationInfo.marker(annotationName));
                    matches.add(new AnnotationMatch("method", m.name(), m.className(), file, m.startLine(), ann));
                }
                for (ClassInfo ci : ctx.parser().parseClasses(file)) {
                    ci.annotations().stream()
                            .filter(a -> a.name().equals(annotationName))
                            .findFirst()
                            .ifPresent(ann -> matches.add(
                                    new AnnotationMatch("class", ci.name(), ci.name(), file, ci.startLine(), ann)));
                }
            }
        }

        if (!ctx.fullOutput() && matches.size() > 30) {
            // Compact: group by type (class vs method), show counts + top 30
            long classCount = matches.stream().filter(m -> "class".equals(m.type())).count();
            long methodCount = matches.stream().filter(m -> "method".equals(m.type())).count();
            var compact = new java.util.LinkedHashMap<String, Object>();
            compact.put("annotation", annotationName);
            compact.put("total", matches.size());
            compact.put("onClasses", classCount);
            compact.put("onMethods", methodCount);
            compact.put("matches", matches.subList(0, 30).stream()
                    .map(m -> {
                        var map = new java.util.LinkedHashMap<String, Object>();
                        map.put("type", m.type());
                        map.put("class", m.className());
                        map.put("name", m.name());
                        return map;
                    }).toList());
            compact.put("truncated", true);
            compact.put("hint", "Use --full to see all " + matches.size() + " matches");
            ctx.formatter().printResult(compact);
        } else {
            ctx.formatter().printAnnotationMatches(matches);
        }
        return matches.size();
    }
}
