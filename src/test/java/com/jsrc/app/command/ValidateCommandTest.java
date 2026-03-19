package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

class ValidateCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void exactMatch_validTrue() throws Exception {
        var result = run("OrderService.process", """
                public class OrderService {
                    public void process(String orderId) {}
                }
                """);
        assertEquals(true, result.get("valid"));
        assertNotNull(result.get("signature"));
    }

    @Test
    void methodNotFound_validFalse_withClosest() throws Exception {
        var result = run("OrderService.processOrder", """
                public class OrderService {
                    public void process(String orderId) {}
                    public void cancel(String orderId) {}
                }
                """);
        assertEquals(false, result.get("valid"));
        assertNotNull(result.get("closest"), "Should suggest closest methods");
        @SuppressWarnings("unchecked")
        var closest = (List<String>) result.get("closest");
        assertTrue(closest.stream().anyMatch(c -> c.contains("process")),
                "Should suggest 'process' as closest to 'processOrder'. Got: " + closest);
    }

    @Test
    void wrongParamCount_suggestsOverloads() throws Exception {
        var result = run("Service.run(String,int,boolean)", """
                public class Service {
                    public void run(String s) {}
                    public void run(String s, int n) {}
                }
                """);
        assertEquals(false, result.get("valid"));
        @SuppressWarnings("unchecked")
        var closest = (List<String>) result.get("closest");
        assertFalse(closest.isEmpty(), "Should suggest existing overloads");
    }

    @Test
    void noClass_uniqueMethod_validTrue() throws Exception {
        var result = run("uniqueMethod", """
                public class A { public void uniqueMethod() {} }
                """, """
                public class B { public void other() {} }
                """);
        assertEquals(true, result.get("valid"));
    }

    @Test
    void noClass_ambiguousMethod_candidates() throws Exception {
        var result = run("run", """
                public class A { public void run() {} }
                """, """
                public class B { public void run() {} }
                """);
        // Ambiguous — should show candidates
        assertNotNull(result.get("candidates"), "Ambiguous method should list candidates");
    }

    @Test
    void methodInDifferentClass_suggestsQualified() throws Exception {
        var result = run("WrongClass.process", """
                public class WrongClass { public void other() {} }
                """, """
                public class RightClass { public void process() {} }
                """);
        assertEquals(false, result.get("valid"));
        @SuppressWarnings("unchecked")
        var closest = (List<String>) result.get("closest");
        assertTrue(closest.stream().anyMatch(c -> c.contains("RightClass")),
                "Should suggest RightClass.process. Got: " + closest);
    }

    @Test
    void genericsInParamType_strippedBeforeCompare() throws Exception {
        var result = run("Svc.process(List)", """
                import java.util.List;
                public class Svc {
                    public void process(List<String> items) {}
                }
                """);
        assertEquals(true, result.get("valid"));
    }

    @Test
    void specialMethodName_dollarAndUnderscore() throws Exception {
        var result = run("Proxy.$init", """
                public class Proxy {
                    public void $init() {}
                    public void _internal() {}
                }
                """);
        assertEquals(true, result.get("valid"));
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String methodRef, String... sources) throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            String src = sources[i];
            String className = "Class" + i;
            int idx = src.indexOf("class ");
            if (idx >= 0) {
                String after = src.substring(idx + 6).trim();
                className = after.split("[\\s{<]")[0];
            }
            Path file = tempDir.resolve(className + ".java");
            Files.writeString(file, src);
            files.add(file);
        }

        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);

        var baos = new ByteArrayOutputStream();
        var ctx = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(baos)), indexed, parser);

        new ValidateCommand(methodRef).execute(ctx);

        String json = baos.toString().trim();
        assertFalse(json.isEmpty(), "Should produce output");
        return (Map<String, Object>) JsonReader.parse(json);
    }
}
