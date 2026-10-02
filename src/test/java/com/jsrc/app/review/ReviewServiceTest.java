package com.jsrc.app.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.model.InvocationKind;
import com.jsrc.app.model.ResolutionLevel;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;
import java.util.Set;
import com.jsrc.app.cli.BudgetContext;
import com.jsrc.app.cli.BudgetProfile;
import com.jsrc.app.parser.HybridJavaParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewServiceTest {
    @Test
    void distinguishesModuleHomonymsAndReportsGraphAmbiguity(@TempDir Path root) throws Exception {
        Path first = write(root, "a/src/main/java/demo/Service.java", """
                package demo;
                public class Service { public int run() { return 1; } }
                """);
        Path second = write(root, "b/src/main/java/demo/Service.java", """
                package demo;
                public class Service { public int run() { return 2; } }
                """);
        commit(root);
        Files.writeString(first, """
                package demo;
                public class Service { public int run() { return 3; } }
                """);

        ReviewReport report = review(root, List.of(first, second));

        assertEquals(1, report.files().size());
        assertEquals("a/src/main/java/demo/Service.java", report.symbols().getFirst().path());
        assertTrue(report.symbols().stream().noneMatch(s -> s.path().startsWith("b/")));
        assertTrue(report.unresolved().stream().anyMatch(s -> s.contains("duplicate qualified type")));
        assertEquals("unknown", ((Map<?, ?>) report.toMap(null).get("summary")).get("risk"));
    }

    @Test
    void preservesRenameDeleteUntrackedAndSpaces(@TempDir Path root) throws Exception {
        Path renamed = write(root, "src/main/java/demo/Old.java", """
                package demo;
                public class Old { public void run() {} }
                """);
        Path deleted = write(root, "src/main/java/demo/Gone.java", """
                package demo;
                public class Gone { public void run() {} }
                """);
        commit(root);
        Path moved = root.resolve("src/main/java/demo/space dir/Old.java");
        Files.createDirectories(moved.getParent());
        Files.move(renamed, moved);
        Files.delete(deleted);
        Path added = write(root, "src/main/java/demo/New.java", """
                package demo;
                public class New { public void run() {} }
                """);
        write(root, "config.yml", "key: value\n");

        ReviewReport report = review(root, List.of(moved, added));

        assertEquals(4, report.files().size());
        assertTrue(report.files().stream().anyMatch(f -> f.status().equals("renamed")
                && f.oldPath().equals("src/main/java/demo/Old.java")
                && f.path().contains("space dir")));
        assertTrue(report.files().stream().anyMatch(f -> f.status().equals("deleted")
                && f.path().endsWith("Gone.java")));
        assertTrue(report.files().stream().anyMatch(f -> f.status().equals("untracked")
                && f.path().endsWith("New.java")));
        assertTrue(report.unresolved().stream().anyMatch(s -> s.contains("config.yml")));
        assertFalse(((Map<?, ?>) report.toMap(null).get("summary")).get("risk").equals("none"));
    }

    @Test
    void cachedRemovalKeepsDeletedAndUntrackedStates(@TempDir Path root) throws Exception {
        Path source = write(root, "src/main/java/demo/Foo.java", """
                package demo;
                public class Foo {}
                """);
        commit(root);
        git(root, "rm", "--cached", "--", "src/main/java/demo/Foo.java");

        ReviewReport report = review(root, List.of(source));

        assertEquals(2, report.files().size());
        assertTrue(report.files().stream().anyMatch(file -> file.status().equals("deleted")));
        assertTrue(report.files().stream().anyMatch(file -> file.status().equals("untracked")));
        assertTrue(report.files().stream().noneMatch(file -> file.status().equals("renamed")));
    }

    @Test
    void ranksPublicContractAndSelectsAfterRanking(@TempDir Path root) throws Exception {
        Path source = write(root, "src/main/java/demo/Api.java", """
                package demo;
                public class Api {
                    public int open() { return 1; }
                    private int local() { return 1; }
                }
                """);
        commit(root);
        Files.writeString(source, """
                package demo;
                public class Api {
                    public long open() { return 1; }
                    private int local() { return 2; }
                }
                """);
        ReviewReport report = review(root, List.of(source));
        BudgetContext budget = new BudgetContext(BudgetProfile.SMALL, 1, 8192,
                false, false, null);

        Map<String, Object> data = report.toMap(budget);
        assertEquals("high", report.symbols().getFirst().risk());
        assertEquals("demo.Api.open()", report.symbols().getFirst().symbol());
        assertEquals(2, ((Map<?, ?>) data.get("summary")).get("changedSymbols"));
        assertEquals(1, ((List<?>) data.get("symbols")).size());
        assertEquals(1, ((Map<?, ?>) data.get("omitted")).get("symbols"));
    }

    @Test
    void proposesOnlyTestFromSameModule(@TempDir Path root) throws Exception {
        Path source = write(root, "module/src/main/java/demo/Service.java", """
                package demo;
                public class Service { public int run() { return 1; } }
                """);
        Path test = write(root, "module/src/test/java/demo/ServiceTest.java", """
                package demo;
                class ServiceTest { void test() {} }
                """);
        Path unrelated = write(root, "module2/src/test/java/demo/ServiceTest.java", """
                package demo;
                class ServiceTest { void test() {} }
                """);
        commit(root);
        Files.writeString(source, """
                package demo;
                public class Service { public int run() { return 2; } }
                """);

        ReviewReport report = review(root, List.of(source, test, unrelated));

        assertEquals(1, report.tests().size());
        assertEquals("module/src/test/java/demo/ServiceTest.java", report.tests().getFirst().path());
        assertEquals("heuristic", report.tests().getFirst().confidence());
    }

    @Test
    void rootSourceDoesNotSuggestSubmoduleHomonym(@TempDir Path root) throws Exception {
        Path source = write(root, "src/main/java/demo/Service.java", """
                package demo;
                public class Service { public int run() { return 1; } }
                """);
        Path rootTest = write(root, "src/test/java/demo/ServiceTest.java", """
                package demo;
                class ServiceTest {}
                """);
        Path submoduleTest = write(root, "module/src/test/java/demo/ServiceTest.java", """
                package demo;
                class ServiceTest {}
                """);
        commit(root);
        Files.writeString(source, """
                package demo;
                public class Service { public int run() { return 2; } }
                """);

        ReviewReport report = review(root, List.of(source, rootTest, submoduleTest));

        assertEquals(1, report.tests().size());
        assertEquals("src/test/java/demo/ServiceTest.java", report.tests().getFirst().path());
    }

    @Test
    void privateFieldChangeIsNotCalledPublicContract(@TempDir Path root) throws Exception {
        Path source = write(root, "src/main/java/demo/Api.java", """
                package demo;
                public class Api { private int state; }
                """);
        commit(root);
        Files.writeString(source, """
                package demo;
                public class Api { private long state; }
                """);

        ReviewReport report = review(root, List.of(source));
        var type = report.symbols().stream()
                .filter(symbol -> symbol.symbol().equals("demo.Api"))
                .findFirst().orElseThrow();
        assertEquals("medium", type.risk());
        assertTrue(type.reasons().stream().noneMatch(reason -> reason.contains("public type contract")));
    }

    @Test
    void typeOnlyChangeSuggestsMatchingTest(@TempDir Path root) throws Exception {
        Path source = write(root, "src/main/java/demo/Api.java", """
                package demo;
                public class Api { private int state; }
                """);
        Path test = write(root, "src/test/java/demo/ApiTest.java", """
                package demo;
                class ApiTest {}
                """);
        commit(root);
        Files.writeString(source, """
                package demo;
                public class Api { private long state; }
                """);

        ReviewReport report = review(root, List.of(source, test));

        assertEquals("src/test/java/demo/ApiTest.java", report.tests().getFirst().path());
    }

    @Test
    void parameterRenameIsNotReportedAsApiContractChange(@TempDir Path root) throws Exception {
        Path source = write(root, "src/main/java/demo/Api.java", """
                package demo;
                public class Api { public int run(int value) { return value; } }
                """);
        commit(root);
        Files.writeString(source, """
                package demo;
                public class Api { public int run(int input) { return input; } }
                """);

        MethodReference target = new MethodReference("demo.Api", "run", List.of("int"), source);
        CallGraph graph = CallGraph.of(Map.of(), Map.of(), Set.of(target),
                Map.of("run", Set.of(target)));
        ReviewReport report = new ReviewService(root, new HybridJavaParser(), graph,
                List.of(source)).review("HEAD");
        var method = report.symbols().stream()
                .filter(symbol -> symbol.symbol().equals("demo.Api.run(int)"))
                .findFirst().orElseThrow();
        assertEquals("unchanged", method.contract());
        assertEquals("low", method.risk());
    }

    @Test
    void doesNotClaimConstructorVisibilityFromKnownParserLimitation(@TempDir Path root) throws Exception {
        Path source = write(root, "src/main/java/demo/Api.java", """
                package demo;
                public class Api { public int run() { return 1; } }
                """);
        commit(root);
        Files.writeString(source, """
                package demo;
                public class Api {
                    private Api() {}
                    public int run() { return 1; }
                }
                """);

        ReviewReport report = review(root, List.of(source));
        var constructor = report.symbols().stream()
                .filter(symbol -> symbol.symbol().equals("demo.Api.Api()"))
                .findFirst().orElseThrow();
        assertEquals("unknown", constructor.contract());
        assertEquals("unknown", constructor.risk());
        assertTrue(report.unresolved().stream().anyMatch(issue -> issue.contains("constructor visibility")));
    }

    @Test
    void usesExactIndexedCallEvidenceToSuggestTest(@TempDir Path root) throws Exception {
        Path source = write(root, "src/main/java/demo/Service.java", """
                package demo;
                public class Service { public int run() { return 1; } }
                """);
        Path test = write(root, "src/test/java/demo/ServiceTest.java", """
                package demo;
                class ServiceTest { void checks() {} }
                """);
        commit(root);
        Files.writeString(source, """
                package demo;
                public class Service { public int run() { return 2; } }
                """);
        MethodReference target = new MethodReference("demo.Service", "run", List.of(), null);
        MethodReference caller = new MethodReference("demo.ServiceTest", "checks", List.of(), null);
        MethodCall edge = new MethodCall(caller, target, 1,
                InvocationKind.VIRTUAL, ResolutionLevel.EXACT, List.of("declared receiver"));
        CallGraph graph = CallGraph.of(Map.of(target, Set.of(edge)),
                Map.of(caller, Set.of(edge)), Set.of(target, caller),
                Map.of("run", Set.of(target)));

        ReviewReport report = new ReviewService(root, new HybridJavaParser(), graph,
                List.of(source, test)).review("HEAD");

        assertEquals("src/test/java/demo/ServiceTest.java", report.tests().getFirst().path());
        assertEquals("calls demo.Service.run()", report.tests().getFirst().reason());
        assertEquals("exact", report.tests().getFirst().confidence());
        assertTrue(report.unresolved().isEmpty(), report.unresolved().toString());
    }

    private static ReviewReport review(Path root, List<Path> files) {
        return new ReviewService(root, new HybridJavaParser(), CallGraph.empty(), files).review("HEAD");
    }

    private static Path write(Path root, String name, String value) throws Exception {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
        return path;
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
