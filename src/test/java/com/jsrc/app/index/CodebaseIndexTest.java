package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.parser.HybridJavaParser;

class CodebaseIndexTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Should build index and save to disk")
    void shouldBuildAndSave() throws IOException {
        Path javaFile = writeFile("Hello.java", """
                package com.test;
                public class Hello {
                    public void greet() {}
                    public String name() { return ""; }
                }
                """);

        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        int reindexed = index.build(new HybridJavaParser(), List.of(javaFile), tempDir, List.of());

        assertEquals(1, reindexed);
        assertEquals(1, index.getEntries().size());

        IndexEntry entry = index.getEntries().getFirst();
        assertFalse(entry.contentHash().isEmpty());
        assertEquals(1, entry.classes().size());
        assertEquals("Hello", entry.classes().getFirst().name());
        assertEquals(2, entry.classes().getFirst().methods().size());

        index.save(tempDir);
        assertTrue(Files.exists(tempDir.resolve(".jsrc/index.json")));
    }

    @Test
    @DisplayName("Should load saved index from disk")
    void shouldLoadFromDisk() throws IOException {
        Path javaFile = writeFile("Service.java", """
                package com.app;
                public class Service {
                    public void process(String input) {}
                }
                """);

        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(javaFile), tempDir, List.of());
        index.save(tempDir);

        // Load from disk
        List<IndexEntry> loaded = CodebaseIndex.load(tempDir);
        assertEquals(1, loaded.size());

        IndexEntry entry = loaded.getFirst();
        assertEquals("Service", entry.classes().getFirst().name());
        assertEquals("com.app", entry.classes().getFirst().packageName());
        assertEquals(1, entry.classes().getFirst().methods().size());
        assertEquals("process", entry.classes().getFirst().methods().getFirst().name());
    }

    @Test
    void shouldPreserveSourceSetAcrossJsonRoundtrip() throws IOException {
        Path javaFile = writeFile("Fixture.java", "class Fixture {}");
        var index = new CodebaseIndex();

        index.build(
                new HybridJavaParser(),
                List.of(javaFile),
                tempDir,
                List.of(),
                List.of(),
                java.util.Map.of(javaFile, com.jsrc.app.project.SourceSet.TEST_FIXTURES));
        index.save(tempDir);

        assertEquals(
                com.jsrc.app.project.SourceSet.TEST_FIXTURES,
                CodebaseIndex.load(tempDir).getFirst().sourceSet());
    }

    @Test
    @DisplayName("Incremental: should skip unchanged files")
    void shouldSkipUnchanged() throws IOException {
        Path javaFile = writeFile("Stable.java", """
                public class Stable {
                    public void doNothing() {}
                }
                """);

        var parser = new HybridJavaParser();
        var index1 = new CodebaseIndex();
        index1.build(parser, List.of(javaFile), tempDir, List.of());
        index1.save(tempDir);

        // Build again with existing index
        List<IndexEntry> existing = CodebaseIndex.load(tempDir);
        var index2 = new CodebaseIndex();
        int reindexed = index2.build(parser, List.of(javaFile), tempDir, existing);

        assertEquals(0, reindexed, "Unchanged file should not be re-indexed");
        assertEquals(1, index2.getEntries().size());
    }

    @Test
    void incrementalBuildRecomputesDispatchTargetsFromDeclaredReceiver() throws IOException {
        Path contract = writeFile("Engine.java", """
                interface Engine { void run(); }
                """);
        Path initialImplementation = writeFile("BaseEngine.java", """
                class BaseEngine implements Engine {
                    public void run() {}
                }
                """);
        Path client = writeFile("Client.java", """
                class Client {
                    Engine engine;
                    void execute() { engine.run(); }
                }
                """);
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser,
                List.of(contract, initialImplementation, client), tempDir, List.of());

        Path addedImplementation = writeFile("NewEngine.java", """
                class NewEngine implements Engine {
                    public void run() {}
                }
                """);
        var added = new CodebaseIndex();
        added.build(parser,
                List.of(contract, initialImplementation, addedImplementation, client),
                tempDir, initial.getEntries());

        assertEquals(java.util.Set.of("BaseEngine", "NewEngine"),
                dispatchTargets(added, "Client", "execute", "run"));

        var removed = new CodebaseIndex();
        removed.build(parser,
                List.of(contract, addedImplementation, client),
                tempDir, added.getEntries());

        assertEquals(java.util.Set.of("NewEngine"),
                dispatchTargets(removed, "Client", "execute", "run"));
    }

    @Test
    void noOpIncrementalBuildPreservesResolvedEdges() throws IOException {
        Path contract = writeFile("Engine.java", """
                interface Engine { void run(); }
                """);
        Path implementation = writeFile("BaseEngine.java", """
                class BaseEngine implements Engine {
                    public void run() {}
                }
                """);
        Path client = writeFile("Client.java", """
                class Client {
                    Engine engine;
                    void execute() { engine.run(); }
                }
                """);
        List<Path> files = List.of(contract, implementation, client);
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, files, tempDir, List.of());
        List<CallEdge> initialEdges = initial.getEntries().stream()
                .flatMap(entry -> entry.callEdges().stream())
                .toList();

        var unchanged = new CodebaseIndex();
        int reindexed = unchanged.build(parser, files, tempDir, initial.getEntries());
        List<CallEdge> unchangedEdges = unchanged.getEntries().stream()
                .flatMap(entry -> entry.callEdges().stream())
                .toList();

        assertEquals(0, reindexed);
        assertEquals(initialEdges, unchangedEdges,
                "A no-op refresh must preserve targets, resolution, and evidence");
    }

    @Test
    @DisplayName("Incremental: should reindex unchanged entries with legacy call edges")
    void shouldReindexLegacyCallEdges() throws IOException {
        Path javaFile = writeFile("Legacy.java", """
                public class Legacy {
                    public void call(String callerValue) { target("value"); }
                    private void target(String value) {}
                }
                """);
        var parser = new HybridJavaParser();
        var initial = new CodebaseIndex();
        initial.build(parser, List.of(javaFile), tempDir, List.of());
        IndexEntry current = initial.getEntries().getFirst();
        CallEdge edge = current.callEdges().getFirst();
        CallEdge legacyEdge = new CallEdge(
                edge.callerClass(), edge.callerMethod(), edge.callerParameterTypes(),
                edge.callerParamCount(),
                edge.calleeClass(), edge.calleeMethod(), edge.line(), edge.argCount());
        IndexEntry legacy = new IndexEntry(
                current.path(), current.contentHash(), current.lastModified(),
                current.classes(), List.of(legacyEdge));

        var rebuilt = new CodebaseIndex();
        int reindexed = rebuilt.build(parser, List.of(javaFile), tempDir, List.of(legacy));

        assertEquals(1, reindexed);
        assertEquals(List.of("String"),
                rebuilt.getEntries().getFirst().callEdges().getFirst().callerParameterTypes());
        assertEquals(List.of("String"),
                rebuilt.getEntries().getFirst().callEdges().getFirst().calleeParameterTypes());
    }

    @Test
    @DisplayName("Incremental: should re-index modified files")
    void shouldReindexModified() throws IOException {
        Path javaFile = writeFile("Mutable.java", """
                public class Mutable {
                    public void v1() {}
                }
                """);

        var parser = new HybridJavaParser();
        var index1 = new CodebaseIndex();
        index1.build(parser, List.of(javaFile), tempDir, List.of());
        index1.save(tempDir);

        // Modify the file
        Files.writeString(javaFile, """
                public class Mutable {
                    public void v2() {}
                    public void v2extra() {}
                }
                """);

        List<IndexEntry> existing = CodebaseIndex.load(tempDir);
        var index2 = new CodebaseIndex();
        int reindexed = index2.build(parser, List.of(javaFile), tempDir, existing);

        assertEquals(1, reindexed, "Modified file should be re-indexed");
        assertEquals(2, index2.getEntries().getFirst().classes().getFirst().methods().size());
    }

    @Test
    @DisplayName("Should return empty list when no index exists")
    void shouldReturnEmptyWhenNoIndex() {
        List<IndexEntry> loaded = CodebaseIndex.load(tempDir);
        assertTrue(loaded.isEmpty());
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private static java.util.Set<String> dispatchTargets(
            CodebaseIndex index,
            String callerClass,
            String callerMethod,
            String calleeMethod) {
        return index.getEntries().stream()
                .flatMap(entry -> entry.callEdges().stream())
                .filter(edge -> edge.callerClass().equals(callerClass)
                        && edge.callerMethod().equals(callerMethod)
                        && edge.calleeMethod().equals(calleeMethod))
                .map(CallEdge::calleeClass)
                .collect(java.util.stream.Collectors.toSet());
    }

    // ---- Call edge extraction ----

    @Test
    @DisplayName("extractCallEdges produces direct method call edges")
    void callEdgesExtracted() throws IOException {
        Path file = writeFile("Caller.java", """
                public class Caller {
                    private Worker w = new Worker();
                    public void run() { w.doWork(); }
                }
                """);
        writeFile("Worker.java", "public class Worker { public void doWork() {} }");

        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(file), tempDir, List.of());

        boolean found = false;
        for (IndexEntry entry : index.getEntries()) {
            for (CallEdge edge : entry.callEdges()) {
                if (edge.callerClass().equals("Caller") && edge.callerMethod().equals("run")
                        && edge.calleeClass().equals("Worker") && edge.calleeMethod().equals("doWork")) {
                    found = true;
                }
            }
        }
        assertTrue(found, "Should find edge Caller.run → Worker.doWork");
    }

    @Test
    @DisplayName("extractCallEdges includes constructor edges")
    void constructorEdgesExtracted() throws IOException {
        Path file = writeFile("Factory.java", """
                public class Factory {
                    public Factory() { init(); }
                    private void init() {}
                }
                """);

        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(file), tempDir, List.of());

        boolean found = false;
        for (IndexEntry entry : index.getEntries()) {
            for (CallEdge edge : entry.callEdges()) {
                if (edge.callerClass().equals("Factory") && edge.callerMethod().equals("Factory")
                        && edge.calleeMethod().equals("init")) {
                    found = true;
                }
            }
        }
        assertTrue(found, "Should find edge Factory.<init> → init");
    }

    @Test
    @DisplayName("extractCallEdges includes new Foo() edges")
    void objectCreationEdgesExtracted() throws IOException {
        Path file = writeFile("Builder.java", """
                public class Builder {
                    public void create() { new Product(); }
                }
                """);
        writeFile("Product.java", "public class Product { public Product() {} }");

        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(file), tempDir, List.of());

        boolean found = false;
        for (IndexEntry entry : index.getEntries()) {
            for (CallEdge edge : entry.callEdges()) {
                if (edge.callerMethod().equals("create")
                        && edge.calleeClass().equals("Product") && edge.calleeMethod().equals("Product")) {
                    found = true;
                }
            }
        }
        assertTrue(found, "Should find edge Builder.create → new Product()");
    }

    @Test
    @DisplayName("Call edges survive save/load roundtrip")
    void callEdgeRoundtrip() throws IOException {
        Path file = writeFile("Roundtrip.java", """
                public class Roundtrip {
                    public void a(String value) { b(value); }
                    public void b(String value) {}
                }
                """);

        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(file), tempDir, List.of());
        CallEdge builtEdge = index.getEntries().stream()
                .flatMap(entry -> entry.callEdges().stream())
                .filter(edge -> edge.callerMethod().equals("a")
                        && edge.calleeMethod().equals("b"))
                .findFirst()
                .orElseThrow();
        assertEquals(com.jsrc.app.model.InvocationKind.VIRTUAL,
                builtEdge.invocationKind(), () -> index.getEntries().toString());
        index.save(tempDir);
        String edgeJson = Files.readString(tempDir.resolve(".jsrc/edges.json"));
        assertTrue(edgeJson.contains("\"invocationKind\":\"VIRTUAL\""), edgeJson);

        // Load from disk
        List<IndexEntry> loaded = CodebaseIndex.load(tempDir);
        assertFalse(loaded.isEmpty());

        boolean hasEdge = false;
        for (IndexEntry entry : loaded) {
            for (CallEdge edge : entry.callEdges()) {
                if (edge.callerMethod().equals("a") && edge.calleeMethod().equals("b")) {
                    hasEdge = true;
                    assertEquals(List.of("String"), edge.callerParameterTypes());
                    assertEquals(List.of("String"), edge.calleeParameterTypes());
                    assertEquals(com.jsrc.app.model.InvocationKind.VIRTUAL,
                            edge.invocationKind());
                    assertEquals(com.jsrc.app.model.ResolutionLevel.EXACT,
                            edge.resolutionLevel());
                    assertEquals(List.of("SYMBOL_RESOLVER", "EXACT_SIGNATURE"),
                            edge.evidence());
                }
            }
        }
        assertTrue(hasEdge, "Call edge should survive save/load roundtrip");
    }

    @Test
    @DisplayName("resolveCalleeClass resolves field types")
    void callEdgeResolvesFieldType() throws IOException {
        Path file = writeFile("WithField.java", """
                public class WithField {
                    private Service svc = new Service();
                    public void run() { svc.process(); }
                }
                """);

        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(file), tempDir, List.of());

        boolean resolved = false;
        for (IndexEntry entry : index.getEntries()) {
            for (CallEdge edge : entry.callEdges()) {
                if (edge.calleeMethod().equals("process") && edge.calleeClass().equals("Service")) {
                    resolved = true;
                }
            }
        }
        assertTrue(resolved, "Should resolve field type 'svc' to 'Service'");
    }

    // ---- Field access chain resolution (jsrc-xum) ----

    @Test
    @DisplayName("TC1: field access on local variable resolves field type")
    void fieldAccessOnLocalVariable() throws IOException {
        Path serviceFile = writeFile("Service.java", """
                public class Service {
                    public StringBuilder resultado = new StringBuilder();
                }
                """);
        Path callerFile = writeFile("Caller.java", """
                public class Caller {
                    public void run() {
                        Service svc = new Service();
                        String s = svc.resultado.toString();
                    }
                }
                """);

        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(serviceFile, callerFile), tempDir, List.of());

        boolean found = index.getEntries().stream()
                .flatMap(e -> e.callEdges().stream())
                .anyMatch(e -> e.calleeMethod().equals("toString")
                        && e.calleeClass().equals("StringBuilder"));
        assertTrue(found, "svc.resultado.toString() should resolve to StringBuilder.toString()");
    }

    @Test
    @DisplayName("TC2: field access on this resolves to field type")
    void fieldAccessOnThis() throws IOException {
        Path file = writeFile("Service.java", """
                public class Service {
                    public StringBuilder buffer = new StringBuilder();
                    public void run() {
                        String s = this.buffer.toString();
                    }
                }
                """);

        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(file), tempDir, List.of());

        boolean found = index.getEntries().stream()
                .flatMap(e -> e.callEdges().stream())
                .anyMatch(e -> e.callerMethod().equals("run")
                        && e.calleeMethod().equals("toString")
                        && e.calleeClass().equals("StringBuilder"));
        assertTrue(found, "this.buffer.toString() should resolve to StringBuilder.toString()");
    }

    @Test
    @DisplayName("TC3: chained field + method call resolves field type")
    void chainedFieldAndMethod() throws IOException {
        Path orderFile = writeFile("Order.java", """
                public class Order {
                    public Customer customer = new Customer();
                }
                """);
        Path customerFile = writeFile("Customer.java", """
                public class Customer {
                    public Address getAddress() { return null; }
                }
                """);
        Path processorFile = writeFile("Processor.java", """
                public class Processor {
                    public void process(Order order) {
                        order.customer.getAddress();
                    }
                }
                """);

        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(),
                List.of(orderFile, customerFile, processorFile), tempDir, List.of());

        boolean found = index.getEntries().stream()
                .flatMap(e -> e.callEdges().stream())
                .anyMatch(e -> e.callerClass().equals("Processor")
                        && e.calleeMethod().equals("getAddress")
                        && e.calleeClass().equals("Customer"));
        assertTrue(found, "order.customer.getAddress() should resolve to Customer.getAddress()");
    }

    @Test
    @DisplayName("IndexedClass stores field types and survives roundtrip")
    void indexedFieldRoundtrip() throws IOException {
        Path file = writeFile("WithFields.java", """
                public class WithFields {
                    private String name;
                    public int count;
                    public void noop() {}
                }
                """);

        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), List.of(file), tempDir, List.of());
        index.save(tempDir);

        List<IndexEntry> loaded = CodebaseIndex.load(tempDir);
        IndexedClass ic = loaded.getFirst().classes().getFirst();
        assertNotNull(ic.fields(), "fields should not be null");
        assertEquals(2, ic.fields().size(), "Should have 2 fields");

        boolean hasName = ic.fields().stream()
                .anyMatch(f -> f.name().equals("name") && f.type().equals("String"));
        boolean hasCount = ic.fields().stream()
                .anyMatch(f -> f.name().equals("count") && f.type().equals("int"));
        assertTrue(hasName, "Should have field name:String");
        assertTrue(hasCount, "Should have field count:int");
    }
}
