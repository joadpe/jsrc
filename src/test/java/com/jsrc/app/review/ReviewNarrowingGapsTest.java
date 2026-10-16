package com.jsrc.app.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.cli.JsrcCliFactory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review of implicit constructors and of members narrowed to private: the implicit access of
 * protected and annotation-member classes, and which callers count as losing access.
 */
class ReviewNarrowingGapsTest {
    @Test
    void implicitConstructorOfProtectedAndAnnotationMemberClassesHasTheirAccess(@TempDir Path root) throws Exception {
        write(root, "Outer.java", "public class Outer { protected static class P { } }");
        write(root, "Ann.java", "public @interface Ann { class Helper { } }");
        commit(root);
        write(root, "Outer.java", "public class Outer { protected static class P { private P() {} } }");
        write(root, "Ann.java", "public @interface Ann { class Helper { private Helper() {} } }");

        var symbols = review(root);

        var protectedClass = symbols.get("demo.Outer$P.P()");
        assertEquals("changed", protectedClass.get("contract"), String.valueOf(protectedClass));
        assertEquals("high", protectedClass.get("risk"), String.valueOf(protectedClass));
        assertTrue(String.valueOf(protectedClass.get("before")).startsWith("protected "), String.valueOf(protectedClass));
        // Members of annotation interfaces are implicitly public, like members of interfaces
        var annotationMember = symbols.get("demo.Ann$Helper.Helper()");
        assertEquals("changed", annotationMember.get("contract"), String.valueOf(annotationMember));
        assertEquals("high", annotationMember.get("risk"), String.valueOf(annotationMember));
        assertTrue(String.valueOf(annotationMember.get("before")).startsWith("public "), String.valueOf(annotationMember));
    }

    @Test
    void privateAccessKeepsTheCallersInsideTheTopLevelClassOnly(@TempDir Path root) throws Exception {
        write(root, "Inner.java", """
                public class Inner {
                    static class Alpha { void run() {} }
                    static class B { void go(Alpha a) { a.run(); } }
                }""");
        write(root, "Shared.java", "public class Shared { static class Beta { void run() {} } }");
        write(root, "Use.java", "class Use { void go(Shared.Beta b) { b.run(); } }");
        commit(root);
        write(root, "Inner.java", """
                public class Inner {
                    static class Alpha { private void run() {} }
                    static class B { void go(Alpha a) { a.run(); } }
                }""");
        write(root, "Shared.java", "public class Shared { static class Beta { private void run() {} } }");

        var symbols = review(root);

        // A sibling nested class keeps access to a private member of its top-level class
        var sibling = symbols.get("demo.Inner$Alpha.run()");
        assertEquals("medium", sibling.get("risk"), String.valueOf(sibling));
        assertFalse(reasons(sibling).stream().anyMatch(reason -> reason.startsWith("narrowed to private")),
                String.valueOf(sibling));
        // A class outside the top-level class loses it
        var outside = symbols.get("demo.Shared$Beta.run()");
        assertEquals("high", outside.get("risk"), String.valueOf(outside));
        assertTrue(reasons(outside).contains("narrowed to private; 1 caller(s) in other classes"),
                String.valueOf(outside));
    }

    @Test
    void inferredCallersDoNotLoseAccess(@TempDir Path root) throws Exception {
        write(root, "Base.java", "class Base { void run() {} }");
        write(root, "Sub.java", "class Sub extends Base { void run() {} }");
        write(root, "Caller.java", "class Caller { void go(Base base) { base.run(); } }");
        commit(root);
        write(root, "Sub.java", "class Sub extends Base { private void run() {} }");

        var run = review(root).get("demo.Sub.run()");

        // The edge Caller.go -> Sub.run is a dispatch target of base.run(), not a use of Sub.run
        assertEquals("inferred", run.get("confidence"), String.valueOf(run));
        assertEquals("medium", run.get("risk"), String.valueOf(run));
        assertFalse(reasons(run).stream().anyMatch(reason -> reason.startsWith("narrowed to private")),
                String.valueOf(run));
    }

    @Test
    void aCallingMethodCountsOnceWhateverItsCallSites(@TempDir Path root) throws Exception {
        write(root, "Api.java", "class Api { void run() {} }");
        write(root, "Caller.java", """
                class Caller {
                    void go(Api api) {
                        api.run();
                        api.run();
                    }
                }""");
        commit(root);
        write(root, "Api.java", "class Api { private void run() {} }");

        var run = review(root).get("demo.Api.run()");

        assertTrue(reasons(run).contains("narrowed to private; 1 caller(s) in other classes"), String.valueOf(run));
    }

    private static List<String> reasons(Map<?, ?> symbol) {
        return ((List<?>) symbol.get("reasons")).stream().map(String::valueOf).toList();
    }

    private static Map<String, Map<?, ?>> review(Path root) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertEquals(0, JsrcCliFactory.create().execute("--dir", root.toString(), "--json", "review"));
        } finally {
            System.setOut(previous);
        }
        var data = (Map<?, ?>) com.jsrc.app.output.JsonReader.parse(out.toString(StandardCharsets.UTF_8).trim());
        return ((List<?>) data.get("symbols")).stream().map(symbol -> (Map<?, ?>) symbol)
                .collect(Collectors.toMap(symbol -> String.valueOf(symbol.get("symbol")), symbol -> symbol));
    }

    private static void write(Path root, String name, String body) throws Exception {
        Path file = root.resolve("src/main/java/demo/" + name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package demo;\n" + body + "\n");
    }

    private static void commit(Path root) throws Exception {
        git(root, "init", "-q");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "Test");
        git(root, "add", ".");
        git(root, "commit", "-qm", "initial");
    }

    private static void git(Path root, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(root.toFile()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
