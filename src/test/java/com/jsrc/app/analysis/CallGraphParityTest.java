package com.jsrc.app.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.parser.model.MethodCall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CallGraphParityTest {

    @TempDir
    Path tempDir;

    @Test
    void directFreshAndReloadedGraphsHaveIdenticalEdges() throws IOException {
        Path factory = writeFile("Factory.java", """
                package parity;
                public class Factory {
                    public Service service = new Service();
                    public Factory() {}
                }
                """);
        Path service = writeFile("Service.java", """
                package parity;
                public class Service {
                    public StringBuilder buffer = new StringBuilder();
                    public Service() {}
                }
                """);
        Path caller = writeFile("Caller.java", """
                package parity;
                public class Caller {
                    public Factory getFactory() { return new Factory(); }
                    public void run() {
                        getFactory().service.buffer.toString();
                    }
                }
                """);
        List<Path> files = List.of(factory, service, caller);

        var directBuilder = new CallGraphBuilder();
        directBuilder.build(files);

        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), files, tempDir, List.of());
        var freshIndexBuilder = new CallGraphBuilder();
        freshIndexBuilder.loadFromIndex(index.getEntries());

        index.save(tempDir);
        var reloadedIndexBuilder = new CallGraphBuilder();
        reloadedIndexBuilder.loadFromIndex(CodebaseIndex.load(tempDir));

        Set<String> directEdges = canonicalEdges(directBuilder.toCallGraph());
        Set<String> freshIndexEdges = canonicalEdges(freshIndexBuilder.toCallGraph());
        Set<String> reloadedIndexEdges = canonicalEdges(reloadedIndexBuilder.toCallGraph());

        assertTrue(freshIndexEdges.stream()
                .anyMatch(edge -> edge.contains("StringBuilder.toString()")));
        assertEquals(freshIndexEdges, reloadedIndexEdges);
        assertEquals(freshIndexEdges, directEdges);
    }

    @Test
    void directGraphIsIndependentOfFileOrderWithHomonymousClasses() throws IOException {
        Path caller = writeFile("app/Caller.java", """
                package app;
                import sales.Service;
                public class Caller {
                    public void run() { Service.execute(); }
                }
                """);
        Path salesService = writeFile("sales/Service.java", """
                package sales;
                public class Service {
                    public static void execute() {}
                }
                """);
        Path supportService = writeFile("support/Service.java", """
                package support;
                public class Service {
                    public static void execute() {}
                }
                """);
        List<Path> callerFirst = List.of(caller, salesService, supportService);

        var directBuilder = new CallGraphBuilder();
        directBuilder.build(callerFirst);

        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), callerFirst, tempDir, List.of());
        var freshIndexBuilder = new CallGraphBuilder();
        freshIndexBuilder.loadFromIndex(index.getEntries());

        index.save(tempDir);
        var reloadedIndexBuilder = new CallGraphBuilder();
        reloadedIndexBuilder.loadFromIndex(CodebaseIndex.load(tempDir));

        Set<String> directEdges = canonicalEdges(directBuilder.toCallGraph());
        Set<String> freshIndexEdges = canonicalEdges(freshIndexBuilder.toCallGraph());
        Set<String> reloadedIndexEdges = canonicalEdges(reloadedIndexBuilder.toCallGraph());

        assertEquals(freshIndexEdges, reloadedIndexEdges);
        assertEquals(freshIndexEdges, directEdges);
    }

    @Test
    void directGraphPrefersSamePackageTypeOverWildcardImport() throws IOException {
        Path caller = writeFile("same/Caller.java", """
                package same;
                import foreign.*;
                public class Caller {
                    public void run() { Service.execute(); }
                }
                """);
        Path sameService = writeFile("same/Service.java", """
                package same;
                public class Service {
                    public static void execute() {}
                }
                """);
        Path foreignService = writeFile("foreign/Service.java", """
                package foreign;
                public class Service {
                    public static void execute() {}
                }
                """);
        List<Path> files = List.of(caller, foreignService, sameService);

        var directBuilder = new CallGraphBuilder();
        directBuilder.build(files);

        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), files, tempDir, List.of());
        var indexBuilder = new CallGraphBuilder();
        indexBuilder.loadFromIndex(index.getEntries());

        Set<String> directEdges = canonicalEdges(directBuilder.toCallGraph());
        Set<String> indexEdges = canonicalEdges(indexBuilder.toCallGraph());
        assertEquals(indexEdges, directEdges);
        assertTrue(directEdges.stream().anyMatch(edge -> edge.contains("same.Service.execute")));
        assertTrue(directEdges.stream().noneMatch(
                edge -> edge.contains("foreign.Service.execute")));
    }

    @Test
    void directAndIndexedGraphsPreserveSemanticMetadata() throws IOException {
        Path service = writeFile("parity/Service.java", """
                package parity;
                public class Service {
                    public void execute() {}
                }
                """);
        Path client = writeFile("parity/Client.java", """
                package parity;
                public class Client {
                    public void run(Service service) { service.execute(); }
                }
                """);
        List<Path> files = List.of(service, client);

        var directBuilder = new CallGraphBuilder();
        directBuilder.build(files);

        var index = new CodebaseIndex();
        index.build(new HybridJavaParser(), files, tempDir, List.of());
        var indexedBuilder = new CallGraphBuilder();
        indexedBuilder.loadFromIndex(index.getEntries());

        MethodCall direct = directBuilder.findMethodsByName("run").stream()
                .flatMap(method -> directBuilder.getCalleesOf(method).stream())
                .findFirst()
                .orElseThrow();
        MethodCall indexed = indexedBuilder.findMethodsByName("run").stream()
                .flatMap(method -> indexedBuilder.getCalleesOf(method).stream())
                .findFirst()
                .orElseThrow();

        assertEquals(indexed.invocationKind(), direct.invocationKind());
        assertEquals(indexed.resolutionLevel(), direct.resolutionLevel());
        assertEquals(indexed.evidence(), direct.evidence());
    }

    private Set<String> canonicalEdges(CallGraph graph) {
        return graph.getAllMethods().stream()
                .flatMap(method -> graph.getCalleesOf(method).stream())
                .map(MethodCall::toString)
                .collect(Collectors.toSet());
    }

    private Path writeFile(String name, String source) throws IOException {
        Path file = tempDir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }
}
