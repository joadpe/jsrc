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
import com.jsrc.app.command.architecture.ValidateCommand;

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

    @Test
    void validate_withoutIndex_matchesSimpleAndQualifiedClassNames() throws Exception {
        List<Path> files = List.of(write("app/Repo.java", """
                package app;
                public class Repo {
                    public String name() { return ""; }
                    public void save(String value, int times) {}
                }
                """));

        for (String ref : List.of("Repo.name", "app.Repo.name", "Repo.save(String,int)")) {
            var result = validate(ref, files, false);
            assertEquals(true, result.get("valid"), ref + " -> " + result);
            assertEquals("app.Repo", result.get("class"), ref + " -> " + result);
        }
    }

    @Test
    void validate_withoutIndex_matchesNestedClassNames() throws Exception {
        List<Path> files = List.of(write("app/Outer.java", """
                package app;
                public class Outer {
                    public static class Inner { public void run() {} }
                }
                """));

        for (String ref : List.of("Inner.run", "Outer.Inner.run", "Outer$Inner.run",
                "app.Outer.Inner.run")) {
            var result = validate(ref, files, false);
            assertEquals(true, result.get("valid"), ref + " -> " + result);
            assertEquals("app.Outer$Inner", result.get("class"), ref + " -> " + result);
        }
    }

    @Test
    void validate_qualifiedNameOfAnotherPackage_reportsInvalid() throws Exception {
        List<Path> files = List.of(write("app/Repo.java", """
                package app;
                public class Repo { public String name() { return ""; } }
                """));

        var result = validate("wrong.Repo.name", files, false);

        assertEquals(false, result.get("valid"), "Got: " + result);
        assertEquals(List.of("app.Repo.name()"), result.get("closest"));
    }

    @Test
    void validate_withoutIndex_reportsSimpleNameOfSeveralPackagesAsAmbiguous() throws Exception {
        List<Path> files = writeDuplicateRepos();

        var ambiguous = validate("Repo.name", files, false);
        var qualified = validate("other.Repo.name", files, false);
        var unique = validate("Repo.extra", files, false);

        assertEquals(true, ambiguous.get("valid"), "Got: " + ambiguous);
        assertEquals(true, ambiguous.get("ambiguous"), "Got: " + ambiguous);
        assertEquals(List.of("app.Repo.name()", "other.Repo.name()"),
                ambiguous.get("candidates"));
        assertEquals("other.Repo", qualified.get("class"), "Got: " + qualified);
        assertEquals("other.Repo", unique.get("class"), "Got: " + unique);
        assertNull(unique.get("ambiguous"));
    }

    @Test
    void validate_withoutIndex_ignoresUnresolvedOwnerOfQualifiedStaticCall() throws Exception {
        List<Path> files = List.of(
                write("com/acme/Repo.java", """
                        package com.acme;
                        public class Repo { public static String find(String id) { return id; } }
                        """),
                write("client/Client.java", """
                        package client;
                        public class Client {
                            public String run() { return com.acme.Repo.find("x"); }
                        }
                        """));

        var result = validate("Repo.find", files, false);
        var typo = validate("Repo.findd", files, false);

        assertEquals("com.acme.Repo", result.get("class"), "Got: " + result);
        assertNull(result.get("ambiguous"));
        assertEquals(List.of("com.acme.Repo.find(String)"), typo.get("closest"), "Got: " + typo);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateBatch_withoutIndex_matchesSimpleAndQualifiedClassNames() throws Exception {
        List<Path> files = new java.util.ArrayList<>(writeDuplicateRepos());
        files.add(write("app/Service.java", """
                package app;
                public class Service { public void handle() {} }
                """));

        var result = validate(
                "Service.handle;app.Service.handle;Repo.extra;Repo.name;wrong.Repo.name",
                files, false);
        var results = (List<Map<String, Object>>) result.get("results");

        assertEquals(4L, ((Number) result.get("valid")).longValue(), "Got: " + result);
        assertEquals("app.Service", results.get(0).get("class"));
        assertEquals("app.Service", results.get(1).get("class"));
        assertEquals("other.Repo", results.get(2).get("class"));
        assertEquals(true, results.get(3).get("ambiguous"));
        assertEquals(List.of("app.Repo.name()", "other.Repo.name()"),
                results.get(3).get("candidates"));
        assertEquals(false, results.get(4).get("valid"));
    }

    @Test
    void validate_withIndex_reportsMatchedOverloadSignatureForAnyClassName() throws Exception {
        List<Path> files = List.of(write("app/Calculator.java", """
                package app;
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                    public int add(int a, int b, int c) { return a + b + c; }
                }
                """));

        // Without parameter types the first declared overload is reported
        for (String ref : List.of("Calculator.add(int,int)", "app.Calculator.add(int,int)",
                "Calculator.add")) {
            var result = validate(ref, files, true);
            assertEquals("app.Calculator", result.get("class"), ref + " -> " + result);
            assertEquals("public int add(int a, int b)", result.get("signature"),
                    ref + " -> " + result);
        }
        var threeArgs = validate("Calculator.add(int,int,int)", files, true);
        assertEquals("public int add(int a, int b, int c)", threeArgs.get("signature"),
                "Got: " + threeArgs);
    }

    @Test
    void validate_withoutIndex_prefersExactQualifiedClassOverLongerName() throws Exception {
        List<Path> files = List.of(
                write("api/User.java", """
                        package api;
                        public class User { public User find(int id) { return this; } }
                        """),
                write("com/acme/api/User.java", """
                        package com.acme.api;
                        public class User { public User find() { return this; } }
                        """));

        var exactWithParams = validate("api.User.find(int)", files, false);
        var exact = validate("api.User.find", files, false);
        var simple = validate("User.find", files, false);

        assertEquals("api.User", exactWithParams.get("class"), "Got: " + exactWithParams);
        assertEquals("api.User", exact.get("class"), "Got: " + exact);
        assertEquals(true, simple.get("ambiguous"), "Got: " + simple);
        assertEquals(List.of("api.User.find(int)", "com.acme.api.User.find()"),
                simple.get("candidates"));
    }

    @Test
    void validate_withoutIndex_keepsDefaultPackageClassNextToPackagedNamesake() throws Exception {
        List<Path> files = List.of(
                write("Repo.java", """
                        public class Repo { public String name(int x) { return ""; } }
                        """),
                write("app/Repo.java", """
                        package app;
                        public class Repo { public String name() { return ""; } }
                        """));

        var simple = validate("Repo.name", files, false);
        var withParams = validate("Repo.name(int)", files, false);

        assertEquals(true, simple.get("ambiguous"), "Got: " + simple);
        assertEquals(List.of("Repo.name(int)", "app.Repo.name()"), simple.get("candidates"));
        assertEquals("Repo", withParams.get("class"), "Got: " + withParams);
    }

    @Test
    void validate_callSiteOfUnknownArity_doesNotMatchMissingOverload() throws Exception {
        List<Path> files = List.of(
                write("app/Repo.java", """
                        package app;
                        import java.util.List;
                        public class Repo {
                            public void save(String a, int b) {}
                            public void save(List<String> items) {}
                        }
                        """),
                write("app/Client.java", """
                        package app;
                        public class Client {
                            private Repo repo = new Repo();
                            public void go() { register(repo::save); }
                            private void register(Object callback) {}
                        }
                        """));

        for (boolean withIndex : List.of(false, true)) {
            for (String ref : List.of("Repo.save()", "Repo.save(int,int,int)")) {
                var result = validate(ref, files, withIndex);
                assertEquals(false, result.get("valid"), ref + " -> " + result);
            }
            var existing = validate("Repo.save(String,int)", files, withIndex);
            assertEquals(true, existing.get("valid"), "Got: " + existing);
        }
    }

    @Test
    void validate_withIndex_reportsVarargsOverloadSignature() throws Exception {
        List<Path> files = List.of(write("app/Logger.java", """
                package app;
                public class Logger {
                    public void log(int level, String msg) { write(msg); }
                    public void log(String... parts) { write(String.join(",", parts)); }
                    private void write(String s) {}
                }
                """));

        for (String ref : List.of("Logger.log(String...)", "Logger.log(String)")) {
            var result = validate(ref, files, true);
            assertEquals("public void log(String parts)", result.get("signature"),
                    ref + " -> " + result);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateBatch_checksParameterCountForAnyClassName() throws Exception {
        List<Path> files = List.of(write("app/Calculator.java", """
                package app;
                public class Calculator {
                    public int add(int a, int b) { return a + b; }
                    public int add(int a, int b, int c) { return a + b + c; }
                }
                """));

        for (boolean withIndex : List.of(false, true)) {
            var result = validate(
                    "Calculator.add(int);app.Calculator.add();Calculator.add(int,int,int)",
                    files, withIndex);
            var results = (List<Map<String, Object>>) result.get("results");

            assertEquals(false, results.get(0).get("valid"), "Got: " + result);
            assertEquals(false, results.get(1).get("valid"), "Got: " + result);
            assertEquals(true, results.get(2).get("valid"), "Got: " + result);
            if (withIndex) {
                assertEquals("public int add(int a, int b, int c)",
                        results.get(2).get("signature"), "Got: " + result);
            }
        }
    }

    // --- helper ---

    /** Two classes named Repo, plus a caller whose calls stay unresolved in the call graph. */
    private List<Path> writeDuplicateRepos() throws Exception {
        return List.of(
                write("app/Repo.java", """
                        package app;
                        public class Repo { public String name() { return ""; } }
                        """),
                write("other/Repo.java", """
                        package other;
                        public class Repo {
                            public String name() { return ""; }
                            public int extra() { return 1; }
                        }
                        """),
                write("client/Client.java", """
                        package client;
                        import app.*;
                        import other.*;
                        public class Client {
                            private Repo repo;
                            public String run() { return repo.name() + repo.name(1); }
                        }
                        """));
    }

    private Path write(String relativePath, String source) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validate(String methodRef, List<Path> files, boolean withIndex)
            throws Exception {
        var parser = new HybridJavaParser();
        IndexedCodebase indexed = null;
        if (withIndex) {
            var index = new CodebaseIndex();
            index.build(parser, files, tempDir, List.of());
            index.save(tempDir);
            indexed = IndexedCodebase.tryLoad(tempDir, files);
            assertNotNull(indexed);
        }
        var baos = new ByteArrayOutputStream();
        var ctx = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(baos)), indexed, parser);

        new ValidateCommand(methodRef).execute(ctx);
        return (Map<String, Object>) JsonReader.parse(baos.toString().trim());
    }

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
