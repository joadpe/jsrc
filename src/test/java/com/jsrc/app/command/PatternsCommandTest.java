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
import com.jsrc.app.command.analysis.PatternsCommand;

class PatternsCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsSlf4jLogging() throws Exception {
        var result = run("""
                import org.slf4j.Logger;
                public class A { public void run() {} }
                """, """
                import org.slf4j.LoggerFactory;
                public class B { public void go() {} }
                """);
        assertTrue(result.get("logging").toString().contains("SLF4J"));
    }

    @Test
    void namingExtractsClassSuffixes() throws Exception {
        var result = run("""
                public class OrderService { public void process() {} }
                """, """
                public class UserService { public void find() {} }
                """, """
                public class PaymentController { public void handle() {} }
                """);
        @SuppressWarnings("unchecked")
        var naming = (Map<String, Object>) result.get("naming");
        @SuppressWarnings("unchecked")
        var suffixes = (Map<String, ?>) naming.get("classSuffixes");
        assertTrue(suffixes.containsKey("Service"), "Should detect Service suffix. Got: " + suffixes);
    }

    @Test
    void annotationsTopN() throws Exception {
        var result = run("""
                public class A {
                    @Override public String toString() { return ""; }
                    @Override public int hashCode() { return 0; }
                }
                """);
        @SuppressWarnings("unchecked")
        var annotations = (Map<String, ?>) result.get("annotations");
        assertTrue(annotations.containsKey("@Override"), "Should include @Override. Got: " + annotations);
    }

    @Test
    void emptyCodebase_noError() throws Exception {
        // No files — should not crash
        List<Path> files = List.of();
        var parser = new HybridJavaParser();
        var baos = new ByteArrayOutputStream();
        var ctx = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(baos)), null, parser);
        new PatternsCommand().execute(ctx);
        String json = baos.toString().trim();
        assertFalse(json.isEmpty());
    }

    @Test
    void customExceptionsDetected() throws Exception {
        var result = run("""
                public class OrderException extends RuntimeException {
                    public OrderException(String msg) { super(msg); }
                }
                """, """
                public class PaymentException extends RuntimeException {
                    public PaymentException(String msg) { super(msg); }
                }
                """);
        @SuppressWarnings("unchecked")
        var errors = (Map<String, Object>) result.get("errorHandling");
        assertTrue(((Number) errors.get("customExceptions")).intValue() >= 2);
    }

    @Test
    void singleClass_noDivideByZero() throws Exception {
        var result = run("""
                public class Only { public void run() {} }
                """);
        assertNotNull(result.get("injection"));
        assertNotNull(result.get("totalClasses"));
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String... sources) throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            String src = sources[i];
            String className = "Class" + i;
            int idx = src.indexOf("class ");
            if (idx >= 0) className = src.substring(idx + 6).trim().split("[\\s{<]")[0];
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
        new PatternsCommand().execute(ctx);
        return (Map<String, Object>) JsonReader.parse(baos.toString().trim());
    }
}
