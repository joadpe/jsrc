package com.jsrc.app.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void reviewsConstructorsWithTheirDeclaredVisibility(@TempDir Path root) throws Exception {
        Path source = write(root, "src/main/java/demo/Api.java", """
                package demo;
                public class Api { public int run() { return 1; } }
                """);
        commit(root);
        Files.writeString(source, """
                package demo;
                public class Api {
                    private Api() {}
                    public Api(int seed) {}
                    public int run() { return 1; }
                }
                """);

        ReviewReport report = review(root, List.of(source));
        // Api had the implicit public Api(); declaring it private narrows it
        var hidden = symbol(report, "demo.Api.Api()");
        var exposed = symbol(report, "demo.Api.Api(int)");
        assertEquals("changed", hidden.contract());
        assertEquals("high", hidden.risk());
        assertTrue(hidden.before().startsWith("public "), hidden.before());
        assertTrue(hidden.after().startsWith("private "), hidden.after());
        assertTrue(hidden.reasons().contains("public method contract changed"), hidden.reasons().toString());
        assertTrue(hidden.reasons().contains("implicit constructor before the change"),
                hidden.reasons().toString());
        assertEquals("added", exposed.contract());
        assertEquals("high", exposed.risk());
        assertTrue(exposed.reasons().contains("public method contract added"));
        assertTrue(report.unresolved().stream().noneMatch(issue -> issue.contains("constructor")),
                report.unresolved().toString());
    }

    @Test
    void implicitConstructorHasTheAccessJavaGivesIt(@TempDir Path root) throws Exception {
        Path local = write(root, "src/main/java/demo/Local.java", """
                package demo;
                class Local { void run() {} }
                """);
        Path shapes = write(root, "src/main/java/demo/Shapes.java", """
                package demo;
                public interface Shapes { class Circle {} }
                """);
        Path color = write(root, "src/main/java/demo/Color.java", """
                package demo;
                public enum Color { RED }
                """);
        commit(root);
        Files.writeString(local, """
                package demo;
                class Local { private Local() {} void run() {} }
                """);
        Files.writeString(shapes, """
                package demo;
                public interface Shapes { class Circle { private Circle() {} } }
                """);
        Files.writeString(color, """
                package demo;
                public enum Color { RED(1); Color(int rgb) {} }
                """);

        ReviewReport report = review(root, List.of(local, shapes, color));

        var packageAccess = symbol(report, "demo.Local.Local()");
        assertEquals("changed", packageAccess.contract());
        assertFalse(packageAccess.before().contains("public"), packageAccess.before());
        assertNotEquals("high", packageAccess.risk());
        // Member classes of interfaces are implicitly public, and so is their implicit constructor
        var interfaceMember = symbol(report, "demo.Shapes$Circle.Circle()");
        assertEquals("changed", interfaceMember.contract());
        assertEquals("high", interfaceMember.risk());
        // new cannot call an enum constructor, so the implicit private one is not compared
        assertTrue(report.symbols().stream().noneMatch(s -> s.symbol().equals("demo.Color.Color()")),
                report.symbols().toString());
        assertEquals("added", symbol(report, "demo.Color.Color(int)").contract());
        assertNotEquals("high", symbol(report, "demo.Color.Color(int)").risk());
    }

    @Test
    void declaredConstructorsReplaceOrRestoreTheImplicitOne(@TempDir Path root) throws Exception {
        Path api = write(root, "src/main/java/demo/Api.java", """
                package demo;
                public class Api { public int run() { return 1; } }
                """);
        Path holder = write(root, "src/main/java/demo/Holder.java", """
                package demo;
                public class Holder { private Holder() {} }
                """);
        Path hidden = write(root, "src/main/java/demo/Hidden.java", """
                package demo;
                public class Hidden {}
                """);
        commit(root);
        Files.writeString(api, """
                package demo;
                public class Api { public Api(int seed) {} public int run() { return 1; } }
                """);
        Files.writeString(holder, """
                package demo;
                public class Holder {}
                """);
        Files.writeString(hidden, """
                package demo;
                class Hidden {}
                """);

        ReviewReport report = review(root, List.of(api, holder, hidden));

        // new Api() no longer compiles: the implicit Api() is gone
        var replaced = symbol(report, "demo.Api.Api()");
        assertEquals("removed", replaced.contract());
        assertEquals("high", replaced.risk());
        assertTrue(replaced.reasons().contains("implicit constructor before the change"),
                replaced.reasons().toString());
        assertEquals("added", symbol(report, "demo.Api.Api(int)").contract());
        // Without declarations Holder gets the implicit public Holder()
        var restored = symbol(report, "demo.Holder.Holder()");
        assertEquals("changed", restored.contract());
        assertEquals("high", restored.risk());
        assertTrue(restored.after().startsWith("public "), restored.after());
        assertTrue(restored.reasons().contains("implicit constructor after the change"),
                restored.reasons().toString());
        // Without declarations on either side, the type symbol reports the narrower access
        assertEquals("high", symbol(report, "demo.Hidden").risk());
        assertTrue(report.symbols().stream().noneMatch(s -> s.symbol().equals("demo.Hidden.Hidden()")),
                report.symbols().toString());
    }

    @Test
    void recordsCompareTheirImplicitCanonicalConstructor(@TempDir Path root) throws Exception {
        Path point = write(root, "src/main/java/demo/Point.java", """
                package demo;
                public record Point(int x, int y) {}
                """);
        Path plain = write(root, "src/main/java/demo/Plain.java", """
                package demo;
                public record Plain(int x) {}
                """);
        Path checked = write(root, "src/main/java/demo/Checked.java", """
                package demo;
                public record Checked(int x) { public Checked { if (x < 0) throw new IllegalStateException(); } }
                """);
        commit(root);
        Files.writeString(point, """
                package demo;
                public record Point(int x, int y, int z) {}
                """);
        Files.writeString(plain, """
                package demo;
                public record Plain(int x) { public Plain { if (x < 0) throw new IllegalStateException(); } }
                """);
        Files.writeString(checked, """
                package demo;
                public record Checked(long x) { public Checked { if (x < 0) throw new IllegalStateException(); } }
                """);

        ReviewReport report = review(root, List.of(point, plain, checked));

        var old = symbol(report, "demo.Point.Point(int, int)");
        assertEquals("removed", old.contract());
        assertEquals("high", old.risk());
        var canonical = symbol(report, "demo.Point.Point(int, int, int)");
        assertEquals("added", canonical.contract());
        assertEquals("high", canonical.risk());
        assertTrue(canonical.reasons().contains("implicit constructor after the change"),
                canonical.reasons().toString());
        // The parsed model has no record constructor declarations: no claim either way
        assertTrue(report.symbols().stream().noneMatch(s -> s.symbol().startsWith("demo.Plain.Plain(")
                        || s.symbol().startsWith("demo.Checked.Checked(")),
                report.symbols().toString());
        assertTrue(report.unresolved().stream().anyMatch(issue -> issue.startsWith("demo.Checked: ")
                && issue.contains("record declares constructors")), report.unresolved().toString());
        assertTrue(report.unresolved().stream().anyMatch(issue -> issue.startsWith("demo.Plain: ")
                && issue.contains("record declares constructors")), report.unresolved().toString());
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

    private static ReviewReport.SymbolReview symbol(ReviewReport report, String name) {
        return report.symbols().stream().filter(symbol -> symbol.symbol().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + report.symbols()));
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
