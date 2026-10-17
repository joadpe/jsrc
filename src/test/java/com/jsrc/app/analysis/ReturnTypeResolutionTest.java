package com.jsrc.app.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.MethodCall;

class ReturnTypeResolutionTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Should resolve chained method calls via return types")
    void shouldResolveChainedCalls() throws Exception {
        writeFile("Catalogo.java", """
                public class Catalogo {
                    public Tenant getTenant() { return null; }
                    public Integer getId() { return null; }
                }
                """);
        writeFile("Tenant.java", """
                public class Tenant {
                    public DefaultLanguage getDefaultLanguage() { return null; }
                }
                """);
        writeFile("DefaultLanguage.java", """
                public class DefaultLanguage {
                    public String getLanguage() { return null; }
                    public String getPais() { return null; }
                }
                """);
        writeFile("GestorProcesos.java", """
                public class GestorProcesos {
                    public void preAccept(Catalogo fto) {
                        String language = fto.getTenant().getDefaultLanguage().getLanguage();
                        String pais = fto.getTenant().getDefaultLanguage().getPais();
                    }
                }
                """);

        var files = Files.list(tempDir).filter(p -> p.toString().endsWith(".java")).toList();
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);

        var loaded = CodebaseIndex.load(tempDir);
        var graph = new CallGraphBuilder();
        graph.loadFromIndex(loaded);

        // Get callees of preAccept
        var targets = graph.findMethodsByName("preAccept");
        assertFalse(targets.isEmpty(), "Should find preAccept");

        Set<String> calleeDescriptions = targets.stream()
                .flatMap(t -> graph.getCalleesOf(t).stream())
                .map(c -> c.callee().className() + "." + c.callee().methodName())
                .collect(Collectors.toSet());

        // All should be resolved (no "?" classes)
        assertFalse(calleeDescriptions.stream().anyMatch(d -> d.startsWith("?.")),
                "No callees should have '?' class. Got: " + calleeDescriptions);

        // Verify specific resolutions
        assertTrue(calleeDescriptions.contains("Catalogo.getTenant"),
                "Should resolve fto.getTenant()");
        assertTrue(calleeDescriptions.contains("Tenant.getDefaultLanguage"),
                "Should resolve .getDefaultLanguage() via Tenant return type");
        assertTrue(calleeDescriptions.contains("DefaultLanguage.getLanguage"),
                "Should resolve .getLanguage() via DefaultLanguage return type");
        assertTrue(calleeDescriptions.contains("DefaultLanguage.getPais"),
                "Should resolve .getPais() via DefaultLanguage return type");
    }

    @Test
    @DisplayName("Should disambiguate return types when same simple name exists in multiple packages")
    void shouldDisambiguateByImports() throws Exception {
        // Two classes named "Tenant" in different packages
        writeFile("TenantModel.java", """
                package com.modelos;
                public class Tenant {
                    public Language getDefaultLanguage() { return null; }
                }
                """);
        writeFile("TenantDTO.java", """
                package com.dto;
                public class Tenant {
                    public String getNombre() { return null; }
                }
                """);
        writeFile("Language.java", """
                package com.modelos;
                public class Language {
                    public String getLanguage() { return null; }
                }
                """);
        // Catalogo imports the modelo Tenant, not the DTO one
        writeFile("Catalogo.java", """
                package com.negocio;
                import com.modelos.Tenant;
                public class Catalogo {
                    public Tenant getTenant() { return null; }
                }
                """);
        writeFile("Gestor.java", """
                package com.negocio;
                public class Gestor {
                    public void process(Catalogo fto) {
                        String language = fto.getTenant().getDefaultLanguage().getLanguage();
                    }
                }
                """);

        var files = Files.list(tempDir).filter(p -> p.toString().endsWith(".java")).toList();
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);

        var loaded = CodebaseIndex.load(tempDir);
        var graph = new CallGraphBuilder();
        graph.loadFromIndex(loaded);

        var targets = graph.findMethodsByName("process");
        Set<String> calleeDescriptions = targets.stream()
                .flatMap(t -> graph.getCalleesOf(t).stream())
                .map(c -> c.callee().className() + "." + c.callee().methodName())
                .collect(Collectors.toSet());

        // Should resolve to Tenant (modelo), not Tenant (DTO)
        assertTrue(calleeDescriptions.contains("com.modelos.Tenant.getDefaultLanguage"),
                "Should resolve getDefaultLanguage via modelo Tenant. Got: " + calleeDescriptions);

        // Should NOT have ?.getDefaultLanguage
        assertFalse(calleeDescriptions.stream().anyMatch(d -> d.startsWith("?.")),
                "All callees should be resolved. Got: " + calleeDescriptions);
    }

    @Test
    @DisplayName("Should leave a chained call unresolved when several same-line receivers declare it")
    void shouldNotGuessBetweenSameLineReceivers() throws Exception {
        writeFile("X.java", """
                public class X {
                    public String getName() { return null; }
                }
                """);
        writeFile("Y.java", """
                public class Y {
                    public String getName() { return null; }
                }
                """);
        writeFile("A.java", """
                public class A {
                    public X getX() { return null; }
                }
                """);
        writeFile("B.java", """
                public class B {
                    public Y getY() { return null; }
                }
                """);
        writeFile("Caller.java", """
                public class Caller {
                    public String both(A a, B b) {
                        return a.getX().getName() + b.getY().getName();
                    }
                    public String single(A a) {
                        return a.getX().getName();
                    }
                }
                """);

        CallGraphBuilder graph = loadGraph();

        // Calls carry a line but no column, so either receiver may own each getName().
        assertEquals(Set.of("A.getX", "B.getY", "?.getName"), calleesOf(graph, "both"));
        assertEquals(Set.of("A.getX", "X.getName"), calleesOf(graph, "single"));
    }

    @Test
    @DisplayName("Should not attribute a nested return type to a top-level class with the same simple name")
    void shouldNotMapNestedSimpleNameToTopLevelClass() throws Exception {
        writeFile("Alpha.java", """
                package app;
                public class Alpha {
                    public static Builder up() { return null; }
                    public static class Builder {
                        public Alpha build() { return null; }
                    }
                }
                """);
        writeFile("Builder.java", """
                package top;
                public class Builder {
                    public void build(String request) {}
                }
                """);
        writeFile("Caller.java", """
                package app;
                public class Caller {
                    public Alpha make() {
                        return Alpha.up().build();
                    }
                }
                """);

        CallGraphBuilder graph = loadGraph();

        // up() returns the nested Alpha.Builder, spelled "Builder"; top.Builder is another class.
        assertTrue(calleesOf(graph, "make").contains("app.Alpha$Builder.build"),
                "Got: " + calleesOf(graph, "make"));
    }

    @Test
    @DisplayName("Should count simple and qualified spellings of one return type as one receiver")
    void shouldTreatSpellingsOfOneReturnTypeAsOneReceiver() throws Exception {
        writeFile("Account.java", """
                package app;
                public class Account {
                    public Customer getCustomer() { return null; }
                }
                """);
        writeFile("Customer.java", """
                package app;
                public class Customer {
                    public String getName() { return null; }
                }
                """);
        writeFile("Order.java", """
                package app;
                public class Order {
                    public Customer getCustomer() { return null; }
                }
                """);
        writeFile("Product.java", """
                package app;
                public class Product {
                    public String getName() { return null; }
                }
                """);
        writeFile("Report.java", """
                package app;
                public class Report {
                    public boolean same(Account account, Order order) {
                        return account.getCustomer().getName().equals(order.getCustomer().getName());
                    }
                }
                """);

        CallGraphBuilder graph = loadGraph();

        // Account is indexed before Customer, so its return type keeps the simple name
        // while Order's is qualified; both spellings name the same receiver class.
        assertTrue(calleesOf(graph, "same").contains("app.Customer.getName"),
                "Got: " + calleesOf(graph, "same"));
    }

    @Test
    @DisplayName("Should judge every call of a pass against the state at the start of the pass")
    void shouldNotLetOneResolutionHideAnotherInTheSamePass() throws Exception {
        writeFile("OneItem.java", """
                package one;
                public class Item {
                    public void first() {}
                    public void second() {}
                }
                """);
        writeFile("TwoItem.java", """
                package two;
                public class Item {}
                """);
        writeFile("Factory.java", """
                package app;
                import two.Item;
                public class Factory {
                    public Item make() { return null; }
                }
                """);
        writeFile("Left.java", """
                package app;
                public class Left {
                    public void left(Factory factory, Ext ext) {
                        factory.make().first();
                        ext.get().second();
                    }
                }
                """);
        writeFile("Right.java", """
                package app;
                public class Right {
                    public void right(Factory factory, Ext ext) {
                        factory.make().second();
                        ext.get().first();
                    }
                }
                """);

        CallGraphBuilder graph = loadGraph();

        // factory.make() returns two.Item, so each caller resolves one call to an
        // undeclared two.Item method. Registering it mid-pass made the other caller's
        // same-named call look non-unique, depending on which caller came first.
        assertTrue(calleesOf(graph, "left").contains("one.Item.second"),
                "Got: " + calleesOf(graph, "left"));
        assertTrue(calleesOf(graph, "right").contains("one.Item.first"),
                "Got: " + calleesOf(graph, "right"));
    }

    private CallGraphBuilder loadGraph() throws IOException {
        List<Path> files;
        try (var stream = Files.list(tempDir)) {
            files = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), files, tempDir, List.of());
        index.save(tempDir);

        var graph = new CallGraphBuilder();
        graph.loadFromIndex(CodebaseIndex.load(tempDir));
        return graph;
    }

    private static Set<String> calleesOf(CallGraphBuilder graph, String methodName) {
        return graph.findMethodsByName(methodName).stream()
                .flatMap(method -> graph.getCalleesOf(method).stream())
                .map(call -> call.callee().className() + "." + call.callee().methodName())
                .collect(Collectors.toSet());
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
