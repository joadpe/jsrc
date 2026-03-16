package com.jsrc.app.architecture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.config.ArchitectureConfig.InvokerDef;

class InvokerResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Resolves reflective invocation with string literal arg")
    void resolveBasic() throws Exception {
        Path file = tempDir.resolve("MyView.java");
        Files.writeString(file, """
                public class MyView {
                    public void onClick() {
                        ejecutarMetodo("processOrder", params);
                    }
                }
                """);

        var invokers = List.of(new InvokerDef("ejecutarMetodo", 0, "adaptadorBean", List.of()));
        var resolver = new InvokerResolver(invokers);
        var calls = resolver.resolve(List.of(file));

        assertEquals(1, calls.size());
        var call = calls.getFirst();
        assertEquals("MyView", call.callerClass());
        assertEquals("onClick", call.callerMethod());
        assertEquals("MyViewAdaptadorBean", call.targetClass());
        assertEquals("processOrder", call.targetMethod());
    }

    @Test
    @DisplayName("Strips caller suffix before applying convention")
    void callerSuffixStrip() throws Exception {
        Path file = tempDir.resolve("FacturacionDetalle.java");
        Files.writeString(file, """
                public class FacturacionDetalle {
                    public void onClick() {
                        ejecutarMetodo("process", params);
                    }
                }
                """);

        var invokers = List.of(new InvokerDef("ejecutarMetodo", 0, "adaptadorBean",
                List.of("Detalle", "Vista")));
        var resolver = new InvokerResolver(invokers);
        var calls = resolver.resolve(List.of(file));

        assertEquals(1, calls.size());
        assertEquals("FacturacionAdaptadorBean", calls.getFirst().targetClass(),
                "Should strip 'Detalle' and append 'AdaptadorBean'");
    }

    @Test
    @DisplayName("Empty callerSuffixes preserves full class name")
    void emptySuffixPreservesName() throws Exception {
        Path file = tempDir.resolve("FacturacionDetalle.java");
        Files.writeString(file, """
                public class FacturacionDetalle {
                    public void onClick() {
                        ejecutarMetodo("process", params);
                    }
                }
                """);

        var invokers = List.of(new InvokerDef("ejecutarMetodo", 0, "adaptadorBean", List.of()));
        var resolver = new InvokerResolver(invokers);
        var calls = resolver.resolve(List.of(file));

        assertEquals(1, calls.size());
        assertEquals("FacturacionDetalleAdaptadorBean", calls.getFirst().targetClass(),
                "With empty suffixes, should preserve full name");
    }

    @Test
    @DisplayName("toCallEdges converts reflective calls to MethodCall edges")
    void toCallEdges() throws Exception {
        Path file = tempDir.resolve("View.java");
        Files.writeString(file, """
                public class View {
                    public void action() {
                        ejecutarMetodo("save", params);
                    }
                }
                """);

        var invokers = List.of(new InvokerDef("ejecutarMetodo", 0, "adaptadorBean", List.of()));
        var resolver = new InvokerResolver(invokers);
        var calls = resolver.resolve(List.of(file));
        var edges = resolver.toCallEdges(calls);

        assertEquals(1, edges.size());
        assertEquals("View", edges.getFirst().caller().className());
        assertEquals("ViewAdaptadorBean", edges.getFirst().callee().className());
        assertEquals("save", edges.getFirst().callee().methodName());
    }

    @Test
    @DisplayName("No reflective calls when method name doesn't match")
    void noMatchingInvoker() throws Exception {
        Path file = tempDir.resolve("NoMatch.java");
        Files.writeString(file, """
                public class NoMatch {
                    public void action() {
                        otherMethod("save", params);
                    }
                }
                """);

        var invokers = List.of(new InvokerDef("ejecutarMetodo", 0, "adaptadorBean", List.of()));
        var resolver = new InvokerResolver(invokers);
        var calls = resolver.resolve(List.of(file));

        assertTrue(calls.isEmpty(), "Should find no reflective calls");
    }
}
