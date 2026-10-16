package com.jsrc.app.index;

import com.jsrc.app.parser.HybridJavaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalEdgeReuseTest {
    @TempDir
    Path root;

    @Test
    void bodyOnlyEditReusesPublishedEdgesAndMatchesCleanBuild() throws IOException {
        Path service = source("Service.java", "class Service { static void one() {} static void two() {} }");
        Path client = source("Client.java", "class Client { void run() { Service.one(); } }");
        Path changed = source("Changed.java", "class Changed { void run() { Service.one(); } }");
        List<Path> files = List.of(service, client, changed);
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, root, List.of());
        initial.saveWithGraph(root, null);
        List<IndexEntry> published = CodebaseIndex.loadPublished(root);
        assertFalse(published.get(1).callEdges().isEmpty());

        Files.writeString(changed, "class Changed { void run() { Service.two(); } }");
        var incremental = new CodebaseIndex();
        assertEquals(1, incremental.build(parser, files, root, published));
        assertSame(published.get(0), incremental.getEntries().get(0));
        assertSame(published.get(1), incremental.getEntries().get(1));

        var clean = new CodebaseIndex();
        clean.build(parser, files, root, List.of());
        assertEquals(clean.getEntries().stream().map(IndexEntry::callEdges).toList(),
                incremental.getEntries().stream().map(IndexEntry::callEdges).toList());
    }

    @Test
    void addedMethodFallsBackToGlobalResolution() throws IOException {
        Path service = source("Service.java", "class Service { static void one() {} }");
        Path client = source("Client.java", "class Client { void run() { Service.one(); } }");
        List<Path> files = List.of(service, client);
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, root, List.of());

        Files.writeString(service, "class Service { static void one() {} static void two() {} }");
        var incremental = new CodebaseIndex();
        incremental.build(parser, files, root, initial.getEntries());
        assertNotSame(initial.getEntries().get(1), incremental.getEntries().get(1));

        var clean = new CodebaseIndex();
        clean.build(parser, files, root, List.of());
        assertEquals(clean.getEntries().stream().map(IndexEntry::callEdges).toList(),
                incremental.getEntries().stream().map(IndexEntry::callEdges).toList());
    }

    @Test
    void changedClassModifierFallsBackAfterPublishedSnapshotReload() throws IOException {
        Path service = source("Service.java", "class Service { void run() {} }");
        Path client = source("Client.java", "class Client { void call(Service service) { service.run(); } }");
        List<Path> files = List.of(service, client);
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, root, List.of());
        initial.saveWithGraph(root, null);
        List<IndexEntry> published = CodebaseIndex.loadPublished(root);

        Files.writeString(service, "final class Service { void run() {} }");
        var incremental = new CodebaseIndex();
        incremental.build(parser, files, root, published);
        assertNotSame(published.get(1), incremental.getEntries().get(1));

        var clean = new CodebaseIndex();
        clean.build(parser, files, root, List.of());
        assertEquals(clean.getEntries().stream().map(IndexEntry::callEdges).toList(),
                incremental.getEntries().stream().map(IndexEntry::callEdges).toList());
    }

    @Test
    void changedTypeKindFallsBackToGlobalResolution() throws IOException {
        Path service = source("Service.java", "class Service { void run() {} }");
        Path client = source("Client.java", "class Client { void call(Service service) { service.run(); } }");
        List<Path> files = List.of(service, client);
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, root, List.of());

        Files.writeString(service, "record Service() { void run() {} }");
        var incremental = new CodebaseIndex();
        incremental.build(parser, files, root, initial.getEntries());
        assertNotSame(initial.getEntries().get(1), incremental.getEntries().get(1));

        var clean = new CodebaseIndex();
        clean.build(parser, files, root, List.of());
        assertEquals(clean.getEntries().stream().map(IndexEntry::callEdges).toList(),
                incremental.getEntries().stream().map(IndexEntry::callEdges).toList());
    }

    @Test
    void missingDeclarationFingerprintFallsBackToGlobalResolution() throws IOException {
        Path service = source("Service.java", "class Service { static void one() {} }");
        Path client = source("Client.java", "class Client { void run() { Service.one(); } }");
        Path changed = source("Changed.java", "class Changed { void run() { Service.one(); } }");
        List<Path> files = List.of(service, client, changed);
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, root, List.of());
        IndexEntry current = initial.getEntries().get(1);
        IndexEntry legacy = new IndexEntry(current.path(), current.contentHash(),
                current.lastModified(), current.sourceSet(), current.classes(),
                current.callEdges(), current.smells(), current.sourceVersion());
        List<IndexEntry> previous = List.of(
                initial.getEntries().get(0), legacy, initial.getEntries().get(2));

        Files.writeString(changed, "class Changed { void run() { Service.one(); Service.one(); } }");
        var incremental = new CodebaseIndex();
        incremental.build(parser, files, root, previous);
        assertNotSame(legacy, incremental.getEntries().get(1));

        var clean = new CodebaseIndex();
        clean.build(parser, files, root, List.of());
        assertEquals(clean.getEntries().stream().map(IndexEntry::callEdges).toList(),
                incremental.getEntries().stream().map(IndexEntry::callEdges).toList());
    }

    @Test
    void changedAnonymousDeclarationInsideMethodFallsBack() throws IOException {
        Path client = source("Client.java", "class Client { void call() { System.gc(); } }");
        Path changed = source("Changed.java", """
                class Changed {
                    void run() {
                        Runnable action = new Runnable() {
                            int value;
                            public void run() {}
                        };
                        action.run();
                    }
                }
                """);
        List<Path> files = List.of(client, changed);
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, root, List.of());

        Files.writeString(changed, """
                class Changed {
                    void run() {
                        Runnable action = new Runnable() {
                            String value;
                            public void run() {}
                        };
                        action.run();
                    }
                }
                """);
        var incremental = new CodebaseIndex();
        incremental.build(parser, files, root, initial.getEntries());
        assertNotSame(initial.getEntries().get(0), incremental.getEntries().get(0));

        var clean = new CodebaseIndex();
        clean.build(parser, files, root, List.of());
        assertEquals(clean.getEntries().stream().map(IndexEntry::callEdges).toList(),
                incremental.getEntries().stream().map(IndexEntry::callEdges).toList());
    }

    @Test
    void changedMethodVisibilityFallsBackToGlobalResolution() throws IOException {
        Path service = source("Service.java", "class Service { public void run() {} }");
        Path client = source("Client.java", "class Client { void call(Service service) { service.run(); } }");
        List<Path> files = List.of(service, client);
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, root, List.of());

        Files.writeString(service, "class Service { private void run() {} }");
        var incremental = new CodebaseIndex();
        incremental.build(parser, files, root, initial.getEntries());
        assertNotSame(initial.getEntries().get(1), incremental.getEntries().get(1));

        var clean = new CodebaseIndex();
        clean.build(parser, files, root, List.of());
        assertEquals(clean.getEntries().stream().map(IndexEntry::callEdges).toList(),
                incremental.getEntries().stream().map(IndexEntry::callEdges).toList());
    }

    @Test
    void changedEntryOrderFallsBackEvenWhenNamesAreUnchanged() throws IOException {
        Path alpha = source("alpha/Duplicate.java", "package alpha; class Duplicate { void run() {} }");
        Path beta = source("beta/Duplicate.java", "package beta; class Duplicate { void run() {} }");
        Path changed = source("Changed.java", "class Changed { void run() { System.gc(); } }");
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, List.of(alpha, beta, changed), root, List.of());

        Files.writeString(changed, "class Changed { void run() { System.runFinalization(); } }");
        var incremental = new CodebaseIndex();
        List<Path> reversed = List.of(beta, alpha, changed);
        incremental.build(parser, reversed, root, initial.getEntries());
        assertNotSame(initial.getEntries().get(1), incremental.getEntries().get(0));

        var clean = new CodebaseIndex();
        clean.build(parser, reversed, root, List.of());
        assertEquals(clean.getEntries().stream().map(IndexEntry::callEdges).toList(),
                incremental.getEntries().stream().map(IndexEntry::callEdges).toList());
    }

    @Test
    void oldReflectiveEdgesPreventReuseWhenInvokerConfigurationIsUnknown() throws IOException {
        Path service = source("Service.java", "class Service { static void one() {} }");
        Path client = source("Client.java", "class Client { void run() { Service.one(); } }");
        Path changed = source("Changed.java", "class Changed { void run() { Service.one(); } }");
        List<Path> files = List.of(service, client, changed);
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, root, List.of());
        CallEdge reflective = new CallEdge("Client", "run", List.of(), 0,
                "Service", "one", List.of(), 1, 0,
                com.jsrc.app.model.InvocationKind.REFLECTIVE,
                com.jsrc.app.model.ResolutionLevel.INFERRED,
                List.of("CONFIGURED_INVOKER"));
        IndexEntry oldClient = initial.getEntries().get(1).withEdges(List.of(reflective));
        List<IndexEntry> previous = List.of(
                initial.getEntries().get(0), oldClient, initial.getEntries().get(2));

        Files.writeString(changed, "class Changed { void run() { Service.one(); Service.one(); } }");
        var incremental = new CodebaseIndex();
        incremental.build(parser, files, root, previous);
        assertNotSame(oldClient, incremental.getEntries().get(1));

        var clean = new CodebaseIndex();
        clean.build(parser, files, root, List.of());
        assertEquals(clean.getEntries().stream().map(IndexEntry::callEdges).toList(),
                incremental.getEntries().stream().map(IndexEntry::callEdges).toList());
    }

    @Test
    void unchangedBuildWithInvokersReusesPublishedEntries() throws IOException {
        List<Path> files = invokerProject();
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, root, List.of(), INVOKERS);
        initial.saveWithGraph(root, null);
        List<IndexEntry> published = CodebaseIndex.loadPublished(root);
        assertTrue(hasReflectiveEdge(published, "OrderHandler", "store"));

        var unchanged = new CodebaseIndex();
        assertEquals(0, unchanged.build(parser, files, root, published, INVOKERS));
        for (int index = 0; index < files.size(); index++) {
            assertSame(published.get(index), unchanged.getEntries().get(index));
        }
        assertFalse(unchanged.refreshedUnchangedEdges());
    }

    @Test
    void bodyOnlyEditKeepsReflectiveEdgesOfUnchangedFiles() throws IOException {
        List<Path> files = invokerProject();
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, root, List.of(), INVOKERS);
        initial.saveWithGraph(root, null);
        List<IndexEntry> published = CodebaseIndex.loadPublished(root);

        Files.writeString(files.get(2), "class Other { void run() { System.runFinalization(); } }");
        var incremental = new CodebaseIndex();
        assertEquals(1, incremental.build(parser, files, root, published, INVOKERS));
        assertSame(published.get(0), incremental.getEntries().get(0));
        assertSame(published.get(1), incremental.getEntries().get(1));
        assertFalse(incremental.refreshedUnchangedEdges());
        assertTrue(hasReflectiveEdge(incremental.getEntries(), "OrderHandler", "store"));

        var clean = new CodebaseIndex();
        clean.build(parser, files, root, List.of(), INVOKERS);
        assertEquals(edges(clean.getEntries()), edges(incremental.getEntries()));
    }

    @Test
    void editedInvokerCallerGetsCurrentReflectiveEdges() throws IOException {
        List<Path> files = invokerProject();
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, root, List.of(), INVOKERS);

        Files.writeString(files.get(0), """
                class OrderView {
                    void save() { invoke("remove"); }
                    void invoke(String name) {}
                }
                """);
        var incremental = new CodebaseIndex();
        assertEquals(1, incremental.build(parser, files, root, initial.getEntries(), INVOKERS));
        assertTrue(hasReflectiveEdge(incremental.getEntries(), "OrderHandler", "remove"));
        assertFalse(hasReflectiveEdge(incremental.getEntries(), "OrderHandler", "store"));

        var clean = new CodebaseIndex();
        clean.build(parser, files, root, List.of(), INVOKERS);
        assertEquals(edges(clean.getEntries()), edges(incremental.getEntries()));
    }

    @Test
    void changedInvokerConfigurationRefreshesUnchangedFiles() throws IOException {
        List<Path> files = invokerProject();
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, root, List.of(), INVOKERS);
        initial.saveWithGraph(root, null);
        List<IndexEntry> published = CodebaseIndex.loadPublished(root);
        var services = List.of(new com.jsrc.app.config.ArchitectureConfig.InvokerDef(
                "invoke", 0, "service", List.of("View")));

        var changed = new CodebaseIndex();
        assertEquals(0, changed.build(parser, files, root, published, services));
        assertTrue(changed.refreshedUnchangedEdges());
        assertNotSame(published.get(0), changed.getEntries().get(0));
        assertTrue(hasReflectiveEdge(changed.getEntries(), "OrderService", "store"));
        assertFalse(hasReflectiveEdge(changed.getEntries(), "OrderHandler", "store"));

        var clean = new CodebaseIndex();
        clean.build(parser, files, root, List.of(), services);
        assertEquals(edges(clean.getEntries()), edges(changed.getEntries()));
        assertEquals(invokerFingerprints(clean.getEntries()),
                invokerFingerprints(changed.getEntries()));
    }

    @Test
    void removedInvokerConfigurationDropsReflectiveEdges() throws IOException {
        List<Path> files = invokerProject();
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, root, List.of(), INVOKERS);

        var removed = new CodebaseIndex();
        assertEquals(0, removed.build(parser, files, root, initial.getEntries(), List.of()));
        assertTrue(removed.refreshedUnchangedEdges());
        assertFalse(hasReflectiveEdge(removed.getEntries(), "OrderHandler", "store"));

        var clean = new CodebaseIndex();
        clean.build(parser, files, root, List.of(), List.of());
        assertEquals(clean.getEntries(), removed.getEntries());
    }

    @Test
    void addedFileRefreshesManyUnchangedFilesLikeACleanBuild() throws IOException {
        List<Path> files = new java.util.ArrayList<>(invokerProject());
        for (int index = 0; index < 96; index++) {
            files.add(source("pkg/Screen" + index + "View.java", """
                    package pkg;
                    class Screen%1$dView {
                        void open() { invoke("load"); new OrderHandler().store(); }
                        void invoke(String name) {}
                    }
                    """.formatted(index)));
        }
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, root, List.of(), INVOKERS);

        files.add(source("Added.java", "class Added { void run() { new Other().run(); } }"));
        var incremental = new CodebaseIndex();
        assertEquals(1, incremental.build(parser, files, root, initial.getEntries(), INVOKERS));
        assertTrue(incremental.refreshedUnchangedEdges());
        assertTrue(hasReflectiveEdge(incremental.getEntries(), "pkg.Screen95Handler", "load"));

        var clean = new CodebaseIndex();
        clean.build(parser, files, root, List.of(), INVOKERS);
        assertEquals(clean.getEntries(), incremental.getEntries());
    }

    private static final List<com.jsrc.app.config.ArchitectureConfig.InvokerDef> INVOKERS =
            List.of(new com.jsrc.app.config.ArchitectureConfig.InvokerDef(
                    "invoke", 0, "handler", List.of("View")));

    /** OrderView reaches OrderHandler.store only through the configured invoker. */
    private List<Path> invokerProject() throws IOException {
        return List.of(
                source("OrderView.java", """
                        class OrderView {
                            void save() { invoke("store"); }
                            void invoke(String name) {}
                        }
                        """),
                source("OrderHandler.java",
                        "class OrderHandler { void store() {} void remove() {} }"),
                source("Other.java", "class Other { void run() { System.gc(); } }"));
    }

    private static boolean hasReflectiveEdge(List<IndexEntry> entries, String calleeClass,
                                             String calleeMethod) {
        return entries.stream()
                .flatMap(entry -> entry.callEdges().stream())
                .anyMatch(edge -> edge.invocationKind()
                        == com.jsrc.app.model.InvocationKind.REFLECTIVE
                        && edge.calleeClass().equals(calleeClass)
                        && edge.calleeMethod().equals(calleeMethod));
    }

    private static List<List<CallEdge>> edges(List<IndexEntry> entries) {
        return entries.stream().map(IndexEntry::callEdges).toList();
    }

    private static List<String> invokerFingerprints(List<IndexEntry> entries) {
        return entries.stream().map(IndexEntry::invokerFingerprint).toList();
    }

    private Path source(String name, String content) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
