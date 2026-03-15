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
                String filePath = ctx.indexed().findFileForClass(m.className());
                matches.add(new AnnotationMatch("method", m.name(), m.className(),
                        Path.of(filePath != null ? filePath : ""), m.startLine(), ann));
            }
            for (ClassInfo ci : ctx.indexed().findClassesByAnnotation(annotationName)) {
                AnnotationInfo ann = ci.annotations().stream()
                        .filter(a -> a.name().equals(annotationName))
                        .findFirst().orElse(AnnotationInfo.marker(annotationName));
                String filePath = ctx.indexed().findFileForClass(ci.name());
                matches.add(new AnnotationMatch("class", ci.name(), ci.name(),
                        Path.of(filePath != null ? filePath : ""), ci.startLine(), ann));
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

        ctx.formatter().printAnnotationMatches(matches);
        return matches.size();
    }
}
