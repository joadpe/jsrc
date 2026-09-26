package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.javaparser.JavaParser;

class EdgeResolverTest {

    @TempDir
    Path tempDir;

    // ---- extractCallEdges ----

    @Test
    @DisplayName("extractCallEdges resolves field type from NameExpr scope")
    void extractCallEdgesResolvesFieldType() throws IOException {
        Path file = writeFile("WithField.java", """
                package com.example;
                public class WithField {
                    private Service svc = new Service();
                    public void run() { svc.process(); }
                }
                """);

        var resolver = new EdgeResolver();
        List<CallEdge> edges = resolver.extractCallEdges(file, new JavaParser());

        assertTrue(edges.stream().anyMatch(e ->
                e.callerClass().equals("com.example.WithField")
                        && e.calleeMethod().equals("process")
                        && e.calleeClass().equals("Service")),
                "Should resolve field type 'svc' to 'Service'");
    }

    @Test
    @DisplayName("extractCallEdges produces FieldAccessExpr markers")
    void extractCallEdgesProducesFieldAccessMarker() throws IOException {
        Path file = writeFile("Caller.java", """
                public class Caller {
                    public void run(Order order) {
                        order.customer.getAddress();
                    }
                }
                """);

        var resolver = new EdgeResolver();
        List<CallEdge> edges = resolver.extractCallEdges(file, new JavaParser());

        // Before marker resolution, should have ?field: marker
        assertTrue(edges.stream().anyMatch(e ->
                e.calleeMethod().equals("getAddress")
                        && e.calleeClass().startsWith("?field:")),
                "Should produce ?field: marker for order.customer.getAddress()");
    }

    @Test
    @DisplayName("extractCallEdges includes constructor edges")
    void extractCallEdgesIncludesConstructors() throws IOException {
        Path file = writeFile("Factory.java", """
                public class Factory {
                    public Factory() { init(); }
                    private void init() {}
                }
                """);

        var resolver = new EdgeResolver();
        List<CallEdge> edges = resolver.extractCallEdges(file, new JavaParser());

        assertTrue(edges.stream().anyMatch(e ->
                e.callerMethod().equals("Factory") && e.calleeMethod().equals("init")),
                "Should extract edge from constructor to init()");
    }

    @Test
    void explicitConstructorInvocationsResolveThisAndSuperTargets() throws IOException {
        Path file = writeFile("Child.java", """
                package app;
                class Base {
                    Base(String value) {}
                }
                class Child extends Base {
                    Child() { this("default"); }
                    Child(String value) { super(value); }
                }
                """);
        var index = new CodebaseIndex();

        index.build(new com.jsrc.app.parser.HybridJavaParser(),
                List.of(file), tempDir, List.of());

        List<CallEdge> edges = index.getEntries().stream()
                .flatMap(entry -> entry.callEdges().stream())
                .filter(edge -> edge.callerClass().equals("app.Child"))
                .toList();
        assertTrue(edges.stream().anyMatch(edge ->
                        edge.callerParameterTypes().isEmpty()
                                && edge.calleeClass().equals("app.Child")
                                && edge.calleeMethod().equals("Child")
                                && edge.calleeParameterTypes().equals(List.of("String"))
                                && edge.invocationKind()
                                == com.jsrc.app.model.InvocationKind.SPECIAL
                                && edge.resolutionLevel()
                                == com.jsrc.app.model.ResolutionLevel.EXACT
                                && edge.evidence().contains("THIS_CONSTRUCTOR_INVOCATION")),
                () -> "Expected exact this(...) constructor edge but got " + edges);
        assertTrue(edges.stream().anyMatch(edge ->
                        edge.callerParameterTypes().equals(List.of("String"))
                                && edge.calleeClass().equals("app.Base")
                                && edge.calleeMethod().equals("Base")
                                && edge.calleeParameterTypes().equals(List.of("String"))
                                && edge.invocationKind()
                                == com.jsrc.app.model.InvocationKind.SPECIAL
                                && edge.resolutionLevel()
                                == com.jsrc.app.model.ResolutionLevel.EXACT
                                && edge.evidence().contains("SUPER_CONSTRUCTOR_INVOCATION")),
                () -> "Expected exact super(...) constructor edge but got " + edges);
    }

    @Test
    void localClassConstructorInvocationBelongsOnlyToLocalConstructor() throws IOException {
        Path file = writeFile("Outer.java", """
                package app;
                class Base {
                    Base() {}
                }
                class Outer {
                    void work() {
                        class Local extends Base {
                            Local() { super(); }
                        }
                    }
                }
                """);
        var resolver = new EdgeResolver();

        List<CallEdge> constructorEdges = resolver.extractCallEdges(file, new JavaParser())
                .stream()
                .filter(edge -> edge.evidence().contains("SUPER_CONSTRUCTOR_INVOCATION"))
                .toList();

        assertTrue(constructorEdges.size() == 1,
                () -> "Expected only the local constructor edge but got " + constructorEdges);
        assertTrue(constructorEdges.stream().allMatch(edge ->
                        edge.callerMethod().equals("Local")
                                && edge.calleeMethod().equals("Base")),
                () -> "Expected Local.Local -> Base.Base but got " + constructorEdges);
    }

    @Test
    void localClassObjectCreationBelongsOnlyToLocalConstructor() throws IOException {
        Path file = writeFile("Outer.java", """
                class Made {}
                class Outer {
                    void work() {
                        class Local {
                            Local() { new Made(); }
                        }
                        new Local();
                    }
                }
                """);
        var resolver = new EdgeResolver();

        List<CallEdge> madeEdges = resolver.extractCallEdges(file, new JavaParser())
                .stream()
                .filter(edge -> edge.calleeMethod().equals("Made"))
                .toList();

        assertTrue(madeEdges.size() == 1 && madeEdges.getFirst().callerMethod().equals("Local"),
                () -> "Expected only Local.Local -> Made.Made but got " + madeEdges);
    }

    @Test
    void localClassConstructorReferenceBelongsOnlyToLocalConstructor() throws IOException {
        Path file = writeFile("Outer.java", """
                class Made {}
                class Outer {
                    void work() {
                        class Local {
                            Local() {
                                java.util.function.Supplier<Made> factory = Made::new;
                            }
                        }
                        new Local();
                    }
                }
                """);
        var resolver = new EdgeResolver();

        List<CallEdge> madeEdges = resolver.extractCallEdges(file, new JavaParser())
                .stream()
                .filter(edge -> edge.calleeMethod().equals("Made"))
                .toList();

        assertTrue(madeEdges.size() == 1 && madeEdges.getFirst().callerMethod().equals("Local"),
                () -> "Expected only Local.Local -> Made::new but got " + madeEdges);
    }

    @Test
    void anonymousClassObjectCreationIsNotAttributedToEnclosingMethod() throws IOException {
        Path file = writeFile("Outer.java", """
                class Made {}
                class Outer {
                    void work() {
                        Runnable task = new Runnable() {
                            public void run() { new Made(); }
                        };
                    }
                }
                """);
        var resolver = new EdgeResolver();

        List<CallEdge> outerEdges = resolver.extractCallEdges(file, new JavaParser())
                .stream()
                .filter(edge -> edge.callerMethod().equals("work"))
                .toList();

        assertTrue(outerEdges.stream().noneMatch(edge -> edge.calleeMethod().equals("Made")),
                () -> "Anonymous body edge leaked into Outer.work: " + outerEdges);
    }

    @Test
    void anonymousClassConstructorReferenceIsNotAttributedToEnclosingMethod() throws IOException {
        Path file = writeFile("Outer.java", """
                class Made {}
                class Outer {
                    void work() {
                        Runnable task = new Runnable() {
                            java.util.function.Supplier<Made> factory = Made::new;
                            public void run() {}
                        };
                    }
                }
                """);
        var resolver = new EdgeResolver();

        List<CallEdge> outerEdges = resolver.extractCallEdges(file, new JavaParser())
                .stream()
                .filter(edge -> edge.callerMethod().equals("work"))
                .toList();

        assertTrue(outerEdges.stream().noneMatch(edge -> edge.calleeMethod().equals("Made")),
                () -> "Anonymous body reference leaked into Outer.work: " + outerEdges);
    }

    @Test
    void localClassMethodCallBelongsOnlyToLocalMethod() throws IOException {
        Path file = writeFile("Outer.java", """
                class Service { void ping() {} }
                class Outer {
                    void work() {
                        class Local {
                            void run(Service service) { service.ping(); }
                        }
                        new Local().run(new Service());
                    }
                }
                """);
        var resolver = new EdgeResolver();

        List<CallEdge> pingEdges = resolver.extractCallEdges(file, new JavaParser())
                .stream()
                .filter(edge -> edge.calleeMethod().equals("ping"))
                .toList();

        assertTrue(pingEdges.size() == 1 && pingEdges.getFirst().callerMethod().equals("run"),
                () -> "Expected only Local.run -> Service.ping but got " + pingEdges);
    }

    @Test
    void anonymousClassMethodCallIsNotAttributedToEnclosingMethod() throws IOException {
        Path file = writeFile("Outer.java", """
                class Service { void ping() {} }
                class Outer {
                    void work(Service service) {
                        Runnable task = new Runnable() {
                            public void run() { service.ping(); }
                        };
                    }
                }
                """);
        var resolver = new EdgeResolver();

        List<CallEdge> outerEdges = resolver.extractCallEdges(file, new JavaParser())
                .stream()
                .filter(edge -> edge.callerMethod().equals("work"))
                .toList();

        assertTrue(outerEdges.stream().noneMatch(edge -> edge.calleeMethod().equals("ping")),
                () -> "Anonymous body call leaked into Outer.work: " + outerEdges);
    }

    @Test
    void localClassLambdaBelongsOnlyToLocalMethod() throws IOException {
        Path file = writeFile("Outer.java", """
                class Made {}
                class Outer {
                    void work() {
                        class Local {
                            void run() { Runnable task = () -> new Made(); }
                        }
                        new Local().run();
                    }
                }
                """);
        var resolver = new EdgeResolver();

        List<CallEdge> madeEdges = resolver.extractCallEdges(file, new JavaParser())
                .stream()
                .filter(edge -> edge.calleeMethod().equals("Made"))
                .toList();

        assertTrue(madeEdges.size() == 1
                        && madeEdges.getFirst().callerMethod().equals("run$lambda$1"),
                () -> "Expected only Local.run$lambda$1 -> Made.Made but got " + madeEdges);
    }

    @Test
    void anonymousClassLambdaIsNotAttributedToEnclosingMethod() throws IOException {
        Path file = writeFile("Outer.java", """
                class Made {}
                class Outer {
                    void work() {
                        Runnable task = new Runnable() {
                            public void run() {
                                Runnable nested = () -> new Made();
                            }
                        };
                    }
                }
                """);
        var resolver = new EdgeResolver();

        List<CallEdge> outerEdges = resolver.extractCallEdges(file, new JavaParser())
                .stream()
                .filter(edge -> edge.callerMethod().startsWith("work"))
                .toList();

        assertTrue(outerEdges.stream().noneMatch(edge -> edge.calleeMethod().equals("Made")),
                () -> "Anonymous body lambda leaked into Outer.work: " + outerEdges);
    }

    @Test
    void nestedLambdaInheritsAncestorLambdaParameterTypes() throws IOException {
        Path file = writeFile("Caller.java", """
                class Service { void ping() {} }
                class Caller {
                    void work() {
                        java.util.function.Consumer<Service> outerTask =
                                (Service outer) -> {
                                    Runnable innerTask = () -> outer.ping();
                                };
                    }
                }
                """);
        var resolver = new EdgeResolver();

        CallEdge pingEdge = resolver.extractCallEdges(file, new JavaParser()).stream()
                .filter(edge -> edge.callerMethod().equals("work$lambda$2")
                        && edge.calleeMethod().equals("ping"))
                .findFirst()
                .orElseThrow();

        assertEquals("Service", pingEdge.calleeClass());
    }

    @Test
    void localClassCallableInheritsEnclosingMethodParameterTypes() throws IOException {
        Path file = writeFile("Caller.java", """
                class Service { void ping() {} }
                class Caller {
                    void work(Service service) {
                        class Local {
                            Local() { service.ping(); }
                        }
                        new Local();
                    }
                }
                """);
        var resolver = new EdgeResolver();

        CallEdge pingEdge = resolver.extractCallEdges(file, new JavaParser()).stream()
                .filter(edge -> edge.callerMethod().equals("Local")
                        && edge.calleeMethod().equals("ping"))
                .findFirst()
                .orElseThrow();

        assertEquals("Service", pingEdge.calleeClass());
    }

    @Test
    void localClassCallableInheritsEnclosingLocalVariableTypes() throws IOException {
        Path file = writeFile("Caller.java", """
                class Service { void ping() {} }
                class Caller {
                    void work() {
                        Service captured = new Service();
                        class Local {
                            Local() { captured.ping(); }
                        }
                        new Local();
                    }
                }
                """);
        var resolver = new EdgeResolver();

        CallEdge pingEdge = resolver.extractCallEdges(file, new JavaParser()).stream()
                .filter(edge -> edge.callerMethod().equals("Local")
                        && edge.calleeMethod().equals("ping"))
                .findFirst()
                .orElseThrow();

        assertEquals("Service", pingEdge.calleeClass());
    }

    @Test
    void siblingBlocksResolveNearestVariableTypes() throws IOException {
        Path file = writeFile("Caller.java", """
                class Alpha { void ping() {} }
                class Beta { void ping() {} }
                class Caller {
                    void work() {
                        { Alpha target = new Alpha(); target.ping(); }
                        { Beta target = new Beta(); target.ping(); }
                    }
                }
                """);
        var resolver = new EdgeResolver();

        List<String> calleeClasses = resolver.extractCallEdges(file, new JavaParser()).stream()
                .filter(edge -> edge.calleeMethod().equals("ping"))
                .sorted(java.util.Comparator.comparingInt(CallEdge::line))
                .map(CallEdge::calleeClass)
                .toList();

        assertEquals(List.of("Alpha", "Beta"), calleeClasses);
    }

    @Test
    void tryResourceIsVisibleOnlyInResourcesAndTryBody() throws IOException {
        Path file = writeFile("Caller.java", """
                class Alpha implements AutoCloseable {
                    void ping() {}
                    public void close() throws Exception {}
                }
                class Beta { void ping() {} }
                class Caller {
                    Beta target = new Beta();
                    void work() {
                        try (Alpha target = new Alpha()) {
                            target.ping();
                        } catch (Exception failure) {
                            target.ping();
                        } finally {
                            target.ping();
                        }
                    }
                }
                """);
        var index = new CodebaseIndex();

        index.build(new com.jsrc.app.parser.HybridJavaParser(),
                List.of(file), tempDir, List.of());

        List<CallEdge> pingEdges = index.getEntries().stream()
                .flatMap(entry -> entry.callEdges().stream())
                .filter(edge -> edge.calleeMethod().equals("ping"))
                .sorted(java.util.Comparator.comparingInt(CallEdge::line))
                .toList();
        assertEquals(List.of("Alpha", "Beta", "Beta"), pingEdges.stream()
                .map(CallEdge::calleeClass)
                .toList());
        assertTrue(pingEdges.stream().allMatch(edge -> edge.resolutionLevel()
                == com.jsrc.app.model.ResolutionLevel.EXACT));
    }

    @Test
    void catchParameterShadowsEnclosingField() throws IOException {
        Path file = writeFile("Caller.java", """
                class Alpha { void ping() {} }
                class BetaProblem extends Exception { void ping() {} }
                class Caller {
                    Alpha problem = new Alpha();
                    void work() {
                        try {
                            throw new BetaProblem();
                        } catch (BetaProblem problem) {
                            problem.ping();
                            class Local {
                                void call() { problem.ping(); }
                            }
                            new Local().call();
                        }
                    }
                }
                """);
        var index = new CodebaseIndex();

        index.build(new com.jsrc.app.parser.HybridJavaParser(),
                List.of(file), tempDir, List.of());

        List<CallEdge> pingEdges = index.getEntries().stream()
                .flatMap(entry -> entry.callEdges().stream())
                .filter(edge -> edge.calleeMethod().equals("ping"))
                .toList();
        assertEquals(2, pingEdges.size());
        assertTrue(pingEdges.stream().allMatch(edge ->
                edge.calleeClass().equals("BetaProblem")));
        assertTrue(pingEdges.stream().allMatch(edge -> edge.resolutionLevel()
                == com.jsrc.app.model.ResolutionLevel.EXACT));
    }

    @Test
    void siblingLambdasResolveOwnVariableTypes() throws IOException {
        Path file = writeFile("Caller.java", """
                class Alpha { void ping() {} }
                class Beta { void ping() {} }
                class Caller {
                    void work() {
                        Runnable first = () -> {
                            Alpha target = new Alpha();
                            target.ping();
                        };
                        Runnable second = () -> {
                            Beta target = new Beta();
                            target.ping();
                        };
                    }
                }
                """);
        var resolver = new EdgeResolver();

        java.util.Map<String, String> calleesByLambda = resolver
                .extractCallEdges(file, new JavaParser()).stream()
                .filter(edge -> edge.calleeMethod().equals("ping"))
                .collect(java.util.stream.Collectors.toMap(
                        CallEdge::callerMethod, CallEdge::calleeClass));

        assertEquals("Alpha", calleesByLambda.get("work$lambda$1"));
        assertEquals("Beta", calleesByLambda.get("work$lambda$2"));
    }

    @Test
    void extractCallEdgesIncludesRecordAndEnumMethods() throws IOException {
        Path file = writeFile("Types.java", """
                package app;
                record Result(String value) {
                    void run() { validate(); }
                    void validate() {}
                }
                enum State {
                    READY;
                    void run() { validate(); }
                    void validate() {}
                }
                """);

        List<CallEdge> edges = new EdgeResolver()
                .extractCallEdges(file, new JavaParser());

        assertTrue(edges.stream().anyMatch(edge ->
                edge.callerClass().equals("app.Result")
                        && edge.callerMethod().equals("run")
                        && edge.calleeMethod().equals("validate")));
        assertTrue(edges.stream().anyMatch(edge ->
                edge.callerClass().equals("app.State")
                        && edge.callerMethod().equals("run")
                        && edge.calleeMethod().equals("validate")));
    }

    @Test
    void nestedTypeInsideRecordUsesBinaryOwner() throws IOException {
        Path file = writeFile("Result.java", """
                package app;
                record Result(String value) {
                    static class Validator {
                        void run() { validate(); }
                        void validate() {}
                    }
                }
                """);

        List<CallEdge> edges = new EdgeResolver()
                .extractCallEdges(file, new JavaParser());

        assertTrue(edges.stream().anyMatch(edge ->
                edge.callerClass().equals("app.Result$Validator")
                        && edge.callerMethod().equals("run")));
    }

    @Test
    void extractsMethodReferenceEdge() throws IOException {
        Path file = writeFile("Client.java", """
                package com.example;
                import java.util.function.Function;
                class Client {
                    Function<String, String> adapter(Mapper mapper) {
                        return mapper::map;
                    }
                }
                interface Mapper {
                    String map(String value);
                }
                """);

        List<CallEdge> edges = new EdgeResolver()
                .extractCallEdges(file, new JavaParser());

        assertTrue(edges.stream().anyMatch(edge ->
                edge.callerClass().equals("com.example.Client")
                        && edge.callerMethod().equals("adapter")
                        && edge.calleeClass().equals("Mapper")
                        && edge.calleeMethod().equals("map")
                        && edge.invocationKind() == com.jsrc.app.model.InvocationKind.METHOD_REFERENCE),
                () -> "Expected method reference edge but got " + edges);
    }

    @Test
    void methodReferencesDistinguishStaticBoundAndUnboundTargets() throws IOException {
        Path clientFile = writeFile("Client.java", """
                package com.example;
                import java.util.function.Function;
                class Client {
                    Function<Widget, Integer> sizeFunction() {
                        return Widget::size;
                    }
                    Function<String, Integer> boundParser(Widget widget) {
                        return widget::parse;
                    }
                    Function<String, Integer> staticParser() {
                        return Widget::parseStatic;
                    }
                }
                """);
        Path widgetFile = writeFile("Widget.java", """
                package com.example;
                class Widget {
                    int size() { return 0; }
                    int parse(String value) { return value.length(); }
                    static int parseStatic(String value) { return value.length(); }
                }
                """);

        var index = new CodebaseIndex();
        index.build(new com.jsrc.app.parser.HybridJavaParser(),
                List.of(clientFile, widgetFile), tempDir, List.of());

        List<CallEdge> edges = index.getEntries().stream()
                .flatMap(entry -> entry.callEdges().stream())
                .filter(edge -> edge.callerClass().equals("com.example.Client"))
                .toList();
        assertTrue(edges.stream().anyMatch(edge ->
                        edge.callerMethod().equals("sizeFunction")
                                && edge.calleeClass().equals("com.example.Widget")
                                && edge.calleeMethod().equals("size")
                                && edge.calleeParameterTypes().isEmpty()
                                && edge.argCount() == 0
                                && edge.resolutionLevel()
                                == com.jsrc.app.model.ResolutionLevel.EXACT
                                && edge.evidence().contains("TYPE_SCOPED_METHOD_REFERENCE")),
                () -> "Expected unbound receiver to be excluded from method parameters: " + edges);
        assertTrue(edges.stream().anyMatch(edge ->
                        edge.callerMethod().equals("boundParser")
                                && edge.calleeMethod().equals("parse")
                                && edge.calleeParameterTypes().equals(List.of("String"))
                                && edge.resolutionLevel()
                                == com.jsrc.app.model.ResolutionLevel.EXACT
                                && !edge.evidence().contains("TYPE_SCOPED_METHOD_REFERENCE")),
                () -> "Expected bound reference parameters to remain unchanged: " + edges);
        assertTrue(edges.stream().anyMatch(edge ->
                        edge.callerMethod().equals("staticParser")
                                && edge.calleeMethod().equals("parseStatic")
                                && edge.calleeParameterTypes().equals(List.of("String"))
                                && edge.resolutionLevel()
                                == com.jsrc.app.model.ResolutionLevel.EXACT
                                && edge.evidence().contains("TYPE_SCOPED_METHOD_REFERENCE")),
                () -> "Expected static reference parameters to remain unchanged: " + edges);
    }

    @Test
    void genericUnboundMethodReferenceErasesSamReceiverType() throws IOException {
        Path file = writeFile("Client.java", """
                package app;
                import java.util.function.Function;
                class Client {
                    Function<Box<String>, String> getter() { return Box::get; }
                }
                class Box<T> {
                    T get() { return null; }
                }
                """);
        var index = new CodebaseIndex();

        index.build(new com.jsrc.app.parser.HybridJavaParser(),
                List.of(file), tempDir, List.of());

        List<CallEdge> edges = index.getEntries().stream()
                .flatMap(entry -> entry.callEdges().stream())
                .filter(edge -> edge.callerMethod().equals("getter"))
                .toList();
        assertTrue(edges.stream().anyMatch(edge ->
                        edge.calleeClass().equals("app.Box")
                                && edge.calleeMethod().equals("get")
                                && edge.calleeParameterTypes().isEmpty()
                                && edge.resolutionLevel()
                                == com.jsrc.app.model.ResolutionLevel.EXACT),
                () -> "Expected erased generic SAM receiver but got " + edges);
    }

    @Test
    void methodReferenceInfersSamTypeFromMethodDeclaredInAnotherFile() throws IOException {
        Path registry = writeFile("Registry.java", """
                package app;
                import java.util.function.Supplier;
                class Registry {
                    void register(Supplier<Widget> factory) {}
                }
                """);
        Path client = writeFile("Client.java", """
                package app;
                class Client {
                    void setup(Registry registry) { registry.register(Widget::new); }
                }
                """);
        Path widget = writeFile("Widget.java", """
                package app;
                class Widget {
                    Widget() {}
                    Widget(String value) {}
                }
                """);
        var index = new CodebaseIndex();

        index.build(new com.jsrc.app.parser.HybridJavaParser(),
                List.of(registry, client, widget), tempDir, List.of());

        List<CallEdge> edges = index.getEntries().stream()
                .flatMap(entry -> entry.callEdges().stream())
                .filter(edge -> edge.callerMethod().equals("setup")
                        && edge.calleeMethod().equals("Widget"))
                .toList();
        assertTrue(edges.stream().anyMatch(edge ->
                        edge.calleeClass().equals("app.Widget")
                                && edge.calleeParameterTypes().isEmpty()
                                && edge.argCount() == 0
                                && edge.resolutionLevel()
                                == com.jsrc.app.model.ResolutionLevel.EXACT
                                && edge.evidence().stream().anyMatch(value ->
                                value.startsWith("FUNCTIONAL_ARGUMENT|"))),
                () -> "Expected global SAM inference for overloaded constructor: " + edges);
    }

    @Test
    void constructorReferenceUsesCanonicalConstructorMethodName() throws IOException {
        Path file = writeFile("Client.java", """
                package com.example;
                import java.util.function.Supplier;
                class Client {
                    Supplier<Widget> factory() {
                        return Widget::new;
                    }
                }
                class Widget {
                    Widget() {}
                    Widget(String value) {}
                }
                """);

        List<CallEdge> edges = new EdgeResolver()
                .extractCallEdges(file, new JavaParser());

        assertTrue(edges.stream().anyMatch(edge ->
                edge.callerClass().equals("com.example.Client")
                        && edge.callerMethod().equals("factory")
                        && edge.calleeClass().equals("Widget")
                        && edge.calleeMethod().equals("Widget")
                        && edge.calleeParameterTypes().isEmpty()
                        && edge.argCount() == 0
                        && edge.invocationKind() == com.jsrc.app.model.InvocationKind.METHOD_REFERENCE),
                () -> "Expected canonical constructor reference edge but got " + edges);

        var index = new CodebaseIndex();
        index.build(new com.jsrc.app.parser.HybridJavaParser(),
                List.of(file), tempDir, List.of());
        List<CallEdge> resolvedEdges = index.getEntries().stream()
                .flatMap(entry -> entry.callEdges().stream())
                .toList();
        assertTrue(resolvedEdges.stream().anyMatch(edge ->
                edge.calleeClass().equals("com.example.Widget")
                        && edge.calleeMethod().equals("Widget")
                        && edge.invocationKind() == com.jsrc.app.model.InvocationKind.METHOD_REFERENCE
                        && edge.resolutionLevel() == com.jsrc.app.model.ResolutionLevel.EXACT),
                () -> "Expected resolved constructor reference edge but got " + resolvedEdges);
    }

    @Test
    void constructorReferenceInfersFunctionalTypeFromMethodArgument() throws IOException {
        Path file = writeFile("Registry.java", """
                import java.util.function.Supplier;
                class Registry {
                    void register() { command(Widget::new); }
                    void command(Supplier<?> factory) {}
                }
                class Widget {
                    Widget() {}
                    Widget(String value) {}
                }
                """);

        List<CallEdge> edges = new EdgeResolver()
                .extractCallEdges(file, new JavaParser());

        assertTrue(edges.stream().anyMatch(edge ->
                        edge.callerMethod().equals("register")
                                && edge.calleeClass().equals("Widget")
                                && edge.calleeMethod().equals("Widget")
                                && edge.calleeParameterTypes().isEmpty()
                                && edge.argCount() == 0),
                () -> "Expected Supplier constructor signature but got " + edges);
    }

    @Test
    void lambdaOwnsConstructorAndMethodReferenceEdges() throws IOException {
        Path file = writeFile("Registration.java", """
                import java.util.function.Supplier;
                class Registration {
                    void register() {
                        Runnable action = () -> {
                            new Widget();
                            Supplier<Widget> factory = Widget::new;
                        };
                    }
                }
                class Widget {
                    Widget() {}
                }
                """);

        List<CallEdge> edges = new EdgeResolver()
                .extractCallEdges(file, new JavaParser());

        List<CallEdge> constructorEdges = edges.stream()
                .filter(edge -> edge.calleeClass().equals("Widget")
                        && edge.calleeMethod().equals("Widget"))
                .toList();
        assertEquals(2, constructorEdges.size(), () -> "Expected both constructor edges: " + edges);
        assertTrue(constructorEdges.stream().allMatch(edge ->
                edge.callerMethod().equals("register$lambda$1")),
                () -> "Lambda edges must not belong to register: " + constructorEdges);
        assertEquals(java.util.Set.of(
                        com.jsrc.app.model.InvocationKind.CONSTRUCTOR,
                        com.jsrc.app.model.InvocationKind.METHOD_REFERENCE),
                constructorEdges.stream()
                        .map(CallEdge::invocationKind)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void resolveSymbolsExpandsInterfaceDispatchWithEvidence() {
        IndexedMethod interfaceMethod = new IndexedMethod(
                "pay", "public abstract void pay()", 2, 2, "void", List.of());
        IndexedMethod implementationMethod = new IndexedMethod(
                "pay", "public void pay()", 2, 2, "void", List.of());
        IndexedMethod callerMethod = new IndexedMethod(
                "run", "public void run(Payment payment)", 2, 4, "void", List.of());

        var entries = new java.util.ArrayList<>(List.of(
                new IndexEntry("Payment.java", "h1", 0,
                        List.of(new IndexedClass(
                                "Payment", "app", 1, 3,
                                true, true, List.of(), List.of(),
                                List.of(interfaceMethod), List.of(), List.of())),
                        List.of()),
                new IndexEntry("CardPayment.java", "h2", 0,
                        List.of(new IndexedClass(
                                "CardPayment", "app", 1, 3,
                                false, false, List.of(), List.of("Payment"),
                                List.of(implementationMethod), List.of(), List.of())),
                        List.of()),
                new IndexEntry("Checkout.java", "h3", 0,
                        List.of(new IndexedClass(
                                "Checkout", "app", 1, 5,
                                false, false, List.of(), List.of(),
                                List.of(callerMethod), List.of(), List.of())),
                        List.of(new CallEdge(
                                "app.Checkout", "run", List.of("Payment"), 1,
                                "Payment", "pay", List.of(), 3, 0)))
        ));

        new EdgeResolver().resolveSymbols(entries);

        List<CallEdge> resolved = entries.stream()
                .flatMap(entry -> entry.callEdges().stream())
                .toList();
        assertTrue(resolved.stream().anyMatch(edge ->
                        edge.calleeClass().equals("app.CardPayment")
                                && edge.invocationKind() == com.jsrc.app.model.InvocationKind.INTERFACE
                                && edge.resolutionLevel() == com.jsrc.app.model.ResolutionLevel.INFERRED
                                && edge.evidence().contains("CHA_IMPLEMENTATION")),
                () -> "Expected inferred interface target but got " + resolved);
        assertFalse(resolved.stream().anyMatch(edge ->
                        edge.calleeClass().equals("app.Payment")),
                () -> "Abstract interface declaration is not a runtime target: " + resolved);
    }

    @Test
    void resolveSymbolsExpandsVirtualOverrides() {
        IndexedMethod baseMethod = new IndexedMethod(
                "work", "public void work()", 2, 2, "void", List.of());
        IndexedMethod childMethod = new IndexedMethod(
                "work", "public void work()", 2, 2, "void", List.of());
        IndexedMethod callerMethod = new IndexedMethod(
                "run", "public void run(Base service)", 2, 4, "void", List.of());

        var entries = new java.util.ArrayList<>(List.of(
                new IndexEntry("Base.java", "h1", 0,
                        List.of(new IndexedClass(
                                "Base", "app", 1, 3,
                                false, false, List.of(), List.of(),
                                List.of(baseMethod), List.of(), List.of())),
                        List.of()),
                new IndexEntry("Child.java", "h2", 0,
                        List.of(new IndexedClass(
                                "Child", "app", 1, 3,
                                false, false, List.of("Base"), List.of(),
                                List.of(childMethod), List.of(), List.of())),
                        List.of()),
                new IndexEntry("Client.java", "h3", 0,
                        List.of(new IndexedClass(
                                "Client", "app", 1, 5,
                                false, false, List.of(), List.of(),
                                List.of(callerMethod), List.of(), List.of())),
                        List.of(new CallEdge(
                                "app.Client", "run", List.of("Base"), 1,
                                "Base", "work", List.of(), 3, 0)))
        ));

        new EdgeResolver().resolveSymbols(entries);

        List<CallEdge> resolved = entries.stream()
                .flatMap(entry -> entry.callEdges().stream())
                .toList();
        assertEquals(java.util.Set.of("app.Base", "app.Child"), resolved.stream()
                .map(CallEdge::calleeClass)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(resolved.stream().allMatch(edge ->
                edge.invocationKind() == com.jsrc.app.model.InvocationKind.VIRTUAL
                        && edge.resolutionLevel() == com.jsrc.app.model.ResolutionLevel.INFERRED));
        assertTrue(resolved.stream().anyMatch(edge ->
                edge.calleeClass().equals("app.Base")
                        && edge.evidence().contains("DECLARED_TARGET")));
        assertTrue(resolved.stream().anyMatch(edge ->
                edge.calleeClass().equals("app.Child")
                        && edge.evidence().contains("CHA_IMPLEMENTATION")));
    }

    // ---- resolveMarkers ----

    @Test
    void resolveSymbolsClassifiesTheResolvedOverloadModifiers() {
        IndexedMethod instanceOverload = new IndexedMethod(
                "run", "public void run(Integer value)", 2, 2, "void", List.of());
        IndexedMethod staticOverload = new IndexedMethod(
                "run", "public static void run(String value)", 3, 3, "void", List.of());
        IndexedMethod callerMethod = new IndexedMethod(
                "call", "public void call()", 2, 4, "void", List.of());
        var entries = new java.util.ArrayList<>(List.of(
                new IndexEntry("Service.java", "h1", 0,
                        List.of(new IndexedClass(
                                "Service", "app", 1, 4,
                                false, false, List.of(), List.of(),
                                List.of(instanceOverload, staticOverload), List.of(), List.of())),
                        List.of()),
                new IndexEntry("Client.java", "h2", 0,
                        List.of(new IndexedClass(
                                "Client", "app", 1, 5,
                                false, false, List.of(), List.of(),
                                List.of(callerMethod), List.of(), List.of())),
                        List.of(new CallEdge(
                                "app.Client", "call", List.of(), 0,
                                "Service", "run", List.of("String"), 3, 1)))
        ));

        new EdgeResolver().resolveSymbols(entries);

        CallEdge resolved = entries.get(1).callEdges().getFirst();
        assertEquals(com.jsrc.app.model.InvocationKind.STATIC, resolved.invocationKind());
        assertEquals(com.jsrc.app.model.ResolutionLevel.EXACT, resolved.resolutionLevel());
    }

    @Test
    void superCallResolvesToDirectSupertypeAsSpecial() throws IOException {
        Path file = writeFile("Child.java", """
                class Base {
                    void load(String value) {}
                }
                class Child extends Base {
                    @Override
                    void load(String value) { super.load(value); }
                }
                """);
        var index = new CodebaseIndex();

        index.build(new com.jsrc.app.parser.HybridJavaParser(),
                List.of(file), tempDir, List.of());

        List<CallEdge> edges = index.getEntries().stream()
                .flatMap(entry -> entry.callEdges().stream())
                .filter(edge -> edge.callerClass().equals("Child")
                        && edge.callerMethod().equals("load"))
                .toList();
        assertTrue(edges.stream().anyMatch(edge ->
                        edge.calleeClass().equals("Base")
                                && edge.calleeMethod().equals("load")
                                && edge.invocationKind()
                                == com.jsrc.app.model.InvocationKind.SPECIAL
                                && edge.resolutionLevel()
                                == com.jsrc.app.model.ResolutionLevel.EXACT),
                () -> "Expected exact super call edge but got " + edges);
    }

    @Test
    @DisplayName("resolveMarkers resolves ?field: markers in entries")
    void resolveMarkersResolvesFieldMarkers() {
        var entries = new java.util.ArrayList<>(List.of(
                new IndexEntry("Order.java", "h1", 0,
                        List.of(new IndexedClass("Order", "com.app", 1, 10,
                                false, false, List.of(), List.of(), List.of(), List.of(), List.of(),
                                List.of(new IndexedField("customer", "Customer")))),
                        List.of()),
                new IndexEntry("Processor.java", "h2", 0,
                        List.of(new IndexedClass("Processor", "com.app", 1, 10,
                                false, false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of())),
                        List.of(new CallEdge("com.app.Processor", "process", List.of(), 1,
                                "?field:com.app.Order.customer", "getAddress", List.of(), 3, 0,
                                com.jsrc.app.model.InvocationKind.VIRTUAL,
                                com.jsrc.app.model.ResolutionLevel.INFERRED,
                                List.of("FIELD_MARKER"))))
        ));

        var resolver = new EdgeResolver();
        resolver.resolveMarkers(entries);

        CallEdge resolved = entries.stream()
                .flatMap(e -> e.callEdges().stream())
                .filter(e -> e.calleeClass().equals("Customer") && e.calleeMethod().equals("getAddress"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("FQCN field marker should resolve to Customer"));
        assertEquals(com.jsrc.app.model.InvocationKind.VIRTUAL, resolved.invocationKind());
        assertEquals(com.jsrc.app.model.ResolutionLevel.INFERRED, resolved.resolutionLevel());
        assertEquals(List.of("FIELD_MARKER"), resolved.evidence());
    }

    @Test
    @DisplayName("resolveMarkers resolves nested ?field:?ret: chains")
    void resolveMarkersResolvesNestedChains() {
        var entries = new java.util.ArrayList<>(List.of(
                new IndexEntry("Factory.java", "h1", 0,
                        List.of(new IndexedClass("Factory", "com.app", 1, 5,
                                false, false, List.of(), List.of(),
                                List.of(), List.of(), List.of(),
                                List.of(new IndexedField("service", "Service")))),
                        List.of()),
                new IndexEntry("Service.java", "h2", 0,
                        List.of(new IndexedClass("Service", "com.app", 1, 5,
                                false, false, List.of(), List.of(),
                                List.of(), List.of(), List.of(),
                                List.of(new IndexedField("buffer", "StringBuilder")))),
                        List.of()),
                new IndexEntry("Caller.java", "h3", 0,
                        List.of(new IndexedClass("Caller", "com.app", 1, 5,
                                false, false, List.of(), List.of(),
                                List.of(new IndexedMethod("getFactory", "Factory getFactory()", 2, 2, "Factory", List.of())),
                                List.of(), List.of(), List.of())),
                        List.of(new CallEdge("com.app.Caller", "run", 0,
                                "?field:?field:?ret:com.app.Caller.getFactory.service.buffer",
                                "toString", 4, 0)))
        ));

        var resolver = new EdgeResolver();
        resolver.resolveMarkers(entries);

        boolean resolved = entries.stream()
                .flatMap(e -> e.callEdges().stream())
                .anyMatch(e -> e.calleeClass().equals("StringBuilder") && e.calleeMethod().equals("toString"));
        assertTrue(resolved, "Nested ?field:?ret: chain should resolve to StringBuilder");
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
