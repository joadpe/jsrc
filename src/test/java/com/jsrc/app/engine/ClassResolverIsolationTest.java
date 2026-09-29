package com.jsrc.app.engine;

import com.jsrc.app.analysis.ClassResolver;
import com.jsrc.app.command.CommandContext;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.parser.CodeParser;
import com.jsrc.app.parser.model.CodeSmell;
import com.jsrc.app.parser.model.MethodInfo;
import java.nio.file.Path;
import com.jsrc.app.parser.model.ClassInfo;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassResolverIsolationTest {
    @Test
    void daoClassificationDoesNotLeakAcrossProjects() {
        var dao = ClassInfo.basic("OrderDao", "a", 1, 1, List.of(), List.of());
        var service = ClassInfo.basic("OrderService", "b", 1, 1, List.of(), List.of());

        assertTrue(ClassResolver.isDaoClass("OrderDao", List.of(dao)));
        assertFalse(ClassResolver.isDaoClass("OrderDao", List.of(service)));
    }
    @Test
    void daoClassificationIsCachedPerExecutionContext() {
        var dao = ClassInfo.basic("OrderDao", "a", 1, 1, List.of(), List.of());
        var service = ClassInfo.basic("OrderService", "b", 1, 1, List.of(), List.of());
        var firstParser = new CountingParser(dao);
        var secondParser = new CountingParser(service);
        var first = new CommandContext(List.of(Path.of("A.java")), ".", null,
                new JsonFormatter(), null, firstParser);
        var second = new CommandContext(List.of(Path.of("B.java")), ".", null,
                new JsonFormatter(), null, secondParser);

        assertTrue(first.daoClasses().contains("OrderDao"));
        assertTrue(first.daoClasses().contains("OrderDao"));
        assertFalse(second.daoClasses().contains("OrderDao"));
        assertEquals(1, firstParser.parseCount);
        assertEquals(1, secondParser.parseCount);
    }

    private static final class CountingParser implements CodeParser {
        private final ClassInfo type;
        private int parseCount;

        private CountingParser(ClassInfo type) { this.type = type; }

        @Override
        public List<ClassInfo> parseClasses(Path path) {
            parseCount++;
            return List.of(type);
        }

        @Override
        public List<MethodInfo> findMethods(Path path, String methodName) { return List.of(); }
        @Override
        public List<MethodInfo> findMethods(Path path, String methodName,
                List<String> parameterTypes) { return List.of(); }
        @Override
        public List<MethodInfo> findAllMethods(Path path) { return List.of(); }
        @Override
        public List<MethodInfo> findMethodsByAnnotation(
                Path path, String annotationName) { return List.of(); }
        @Override
        public List<CodeSmell> detectSmells(Path path) { return List.of(); }
        @Override
        public String getLanguage() { return "java"; }
    }

}
