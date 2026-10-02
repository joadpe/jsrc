package com.jsrc.app.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.jsrc.app.cli.JsrcCliFactory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewCommandTest {
    @Test
    void reportsChangedMethodWithoutTreatingOtherOverloadsAsChanged(@TempDir Path root) throws Exception {
        Path source = root.resolve("src/main/java/demo/Service.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package demo;
                public class Service {
                    public int run(int value) { return value; }
                    public int run() { return 1; }
                }
                """);
        git(root, "init", "-q");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "Test");
        git(root, "add", ".");
        git(root, "commit", "-qm", "initial");
        Files.writeString(source, """
                package demo;
                public class Service {
                    public int run(int value) { return value + 1; }
                    public int run() { return 1; }
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertEquals(0, JsrcCliFactory.create().execute("--dir", root.toString(), "--json", "review"));
        } finally {
            System.setOut(previous);
        }
        String result = out.toString(StandardCharsets.UTF_8);
        assertTrue(result.contains("demo.Service.run(int)"), result);
        assertTrue(result.contains("\"changedSymbols\":1"), result);
    }

    @Test
    void invalidRefIsAnErrorRatherThanAnEmptyReview(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("README.md"), "fixture");
        git(root, "init", "-q");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "Test");
        git(root, "add", ".");
        git(root, "commit", "-qm", "initial");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertEquals(2, JsrcCliFactory.create().execute("--dir", root.toString(),
                    "--json", "review", "does-not-exist"));
        } finally {
            System.setOut(previous);
        }
        assertFalse(out.toString(StandardCharsets.UTF_8).contains("changedFiles"));
    }

    @Test
    void tinyVersionedOutputRetainsTotalsAndOmissionReasons(@TempDir Path root) throws Exception {
        Path source = root.resolve("src/main/java/demo/Api.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package demo;
                public class Api { public int run() { return 1; } }
                """);
        git(root, "init", "-q");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "Test");
        git(root, "add", ".");
        git(root, "commit", "-qm", "initial");
        Files.writeString(source, """
                package demo;
                public class Api { public int run() { return 2; } }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertEquals(0, JsrcCliFactory.create().execute("--dir", root.toString(),
                    "--json", "--protocol", "1", "--budget", "tiny", "review"));
        } finally {
            System.setOut(previous);
        }
        var envelope = (java.util.Map<?, ?>) com.jsrc.app.output.JsonReader.parse(
                out.toString(StandardCharsets.UTF_8).trim());
        assertEquals("urn:jsrc:output:review:1", envelope.get("schema"));
        var data = (java.util.Map<?, ?>) envelope.get("data");
        assertEquals(1L, ((java.util.Map<?, ?>) data.get("summary")).get("changedFiles"));
        assertTrue(data.containsKey("omitted"), data.toString());
    }

    @Test
    void textOutputUsesTheSameBudgetedSelection(@TempDir Path root) throws Exception {
        Path first = root.resolve("src/main/java/demo/First.java");
        Path second = root.resolve("src/main/java/demo/Second.java");
        Files.createDirectories(first.getParent());
        Files.writeString(first, "package demo; public class First { public int run() { return 1; } }");
        Files.writeString(second, "package demo; public class Second { public int run() { return 1; } }");
        git(root, "init", "-q");
        git(root, "config", "user.email", "test@example.com");
        git(root, "config", "user.name", "Test");
        git(root, "add", ".");
        git(root, "commit", "-qm", "initial");
        Files.writeString(first, "package demo; public class First { public int run() { return 2; } }");
        Files.writeString(second, "package demo; public class Second { public int run() { return 2; } }");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertEquals(0, JsrcCliFactory.create().execute("--dir", root.toString(),
                    "--md", "--budget", "tiny", "--limit", "1", "review"));
        } finally {
            System.setOut(previous);
        }
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("omitted"), output);
        assertFalse(output.contains("Second.run"), output);
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
