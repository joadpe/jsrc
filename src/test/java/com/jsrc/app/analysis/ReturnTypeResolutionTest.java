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
        writeFile("Facturacion.java", """
                public class Facturacion {
                    public Explotacion getExplotacion() { return null; }
                    public Integer getId() { return null; }
                }
                """);
        writeFile("Explotacion.java", """
                public class Explotacion {
                    public IdiomaDefecto getIdiomaDefecto() { return null; }
                }
                """);
        writeFile("IdiomaDefecto.java", """
                public class IdiomaDefecto {
                    public String getIdioma() { return null; }
                    public String getPais() { return null; }
                }
                """);
        writeFile("GestorProcesos.java", """
                public class GestorProcesos {
                    public void preAcepta(Facturacion fto) {
                        String idioma = fto.getExplotacion().getIdiomaDefecto().getIdioma();
                        String pais = fto.getExplotacion().getIdiomaDefecto().getPais();
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

        // Get callees of preAcepta
        var targets = graph.findMethodsByName("preAcepta");
        assertFalse(targets.isEmpty(), "Should find preAcepta");

        Set<String> calleeDescriptions = targets.stream()
                .flatMap(t -> graph.getCalleesOf(t).stream())
                .map(c -> c.callee().className() + "." + c.callee().methodName())
                .collect(Collectors.toSet());

        // All should be resolved (no "?" classes)
        assertFalse(calleeDescriptions.stream().anyMatch(d -> d.startsWith("?.")),
                "No callees should have '?' class. Got: " + calleeDescriptions);

        // Verify specific resolutions
        assertTrue(calleeDescriptions.contains("Facturacion.getExplotacion"),
                "Should resolve fto.getExplotacion()");
        assertTrue(calleeDescriptions.contains("Explotacion.getIdiomaDefecto"),
                "Should resolve .getIdiomaDefecto() via Explotacion return type");
        assertTrue(calleeDescriptions.contains("IdiomaDefecto.getIdioma"),
                "Should resolve .getIdioma() via IdiomaDefecto return type");
        assertTrue(calleeDescriptions.contains("IdiomaDefecto.getPais"),
                "Should resolve .getPais() via IdiomaDefecto return type");
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
