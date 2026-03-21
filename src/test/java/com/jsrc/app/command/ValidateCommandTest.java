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
    void validate_withParamTypes_findsMethod() throws Exception {
        var result = runValidate("Calculator.add(int,int)",
                """
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                    public int add(int a, int b, int c) { return a + b + c; }
                }
                """);
        assertEquals(true, result.get("valid"),
                "Should find Calculator.add(int,int). Got: " + result);
    }

    @Test
    void validate_withWrongParamCount_reportsInvalid() throws Exception {
        var result = runValidate("Calculator.add(int,int,int,int)",
                """
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                    public int add(int a, int b, int c) { return a + b + c; }
                }
                """);
        assertEquals(false, result.get("valid"),
                "Should NOT find add with 4 params. Got: " + result);
    }

    @Test
    void validate_withoutParamTypes_findsMethod() throws Exception {
        var result = runValidate("Calculator.add",
                """
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                }
                """);
        assertEquals(true, result.get("valid"));
    }

    @Test
    void validate_nonExistentMethod_reportsInvalid() throws Exception {
        var result = runValidate("Calculator.multiply",
                """
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                }
                """);
        assertEquals(false, result.get("valid"));
    }

    @Test
    void validate_withGenericParamTypes_findsMethod() throws Exception {
        // Generics should be stripped: Map<String,Integer> → Map
        var result = runValidate("Service.process(String,Map)",
                """
                import java.util.Map;
                public class Service {
                    public void process(String name, Map<String, Integer> data) {}
                }
                """);
        assertEquals(true, result.get("valid"),
                "Should find process(String,Map) stripping generics. Got: " + result);
    }

    @Test
    void validate_protectedMethod_findsIt() throws Exception {
        var result = runValidate("Base.configure",
                """
                public class Base {
                    protected void configure() {}
                }
                """);
        assertEquals(true, result.get("valid"),
                "Should find protected methods. Got: " + result);
    }

    @Test
    void validate_privateMethod_findsIt() throws Exception {
        var result = runValidate("Util.helper",
                """
                public class Util {
                    private void helper() {}
                }
                """);
        assertEquals(true, result.get("valid"),
                "Should find private methods. Got: " + result);
    }

    // --- helper ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> runValidate(String methodRef, String... sources) throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        for (String src : sources) {
            String className = "Class0";
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

        new ValidateCommand(methodRef).execute(ctx);
        String json = baos.toString().trim();
        if (json.isEmpty()) return Map.of("valid", false, "reason", "empty output");
        return (Map<String, Object>) JsonReader.parse(json);
    }
}
