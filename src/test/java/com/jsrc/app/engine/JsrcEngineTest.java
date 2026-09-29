package com.jsrc.app.engine;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.FieldInfo;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;
import com.jsrc.app.project.SourceSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class JsrcEngineTest {
    private final JsrcEngine engine = new JsrcEngine();

    @Test
    void overviewWorksWithAnInMemoryProject() {
        var service = ClassInfo.basic("Service", "demo", 1, 5, List.of(), List.of());
        var source = new JsrcEngine.OverviewSource() {
            public List<ClassInfo> classes() { return List.of(service); }
            public int fileCount() { return 2; }
            public Map<SourceSet, Long> sourceSetCounts() { return Map.of(SourceSet.MAIN, 2L); }
            public com.jsrc.app.project.ProjectModel projectModel() { return null; }
        };

        var result = engine.overview(source);

        assertEquals(2, result.totalFiles());
        assertEquals(1, result.totalClasses());
        assertEquals(List.of("demo"), result.packages());
        assertEquals(List.of("Service"), result.topClassNames());
    }

    @Test
    void resolvesAFieldAndIndexedMethodWithoutCli() {
        var controller = new ClassInfo("Controller", "demo", 1, 8,
                List.of(), List.of(), "", List.of(), List.of(), false,
                List.of(new FieldInfo("service", "Service")));
        var source = new JsrcEngine.ResolutionSource() {
            public List<ClassInfo> classes() { return List.of(controller); }
            public Optional<JsrcEngine.MethodDetails> findMethod(
                    String typeName, String methodName) {
                return typeName.equals("Service") && methodName.equals("process")
                        ? Optional.of(new JsrcEngine.MethodDetails("process()", "void"))
                        : Optional.empty();
            }
        };

        var result = engine.resolve(source, "Controller.service.process");

        assertEquals(ResolutionResult.Status.FOUND, result.status());
        assertEquals("Service", result.resolvedType());
        assertEquals("field", result.resolvedVia());
        assertEquals("void", result.returnType());
    }

    @Test
    void reportsAmbiguousCallersWithQualifiedSuggestions() {
        var sales = new MethodReference("Service", "process", List.of("String"), null);
        var support = new MethodReference("OtherService", "process", List.of("Integer"), null);
        var graph = CallGraph.of(Map.of(), Map.of(), Set.of(sales, support),
                Map.of("process", Set.of(sales, support)));
        var source = new JsrcEngine.CallersSource() {
            public CallGraph callGraph() { return graph; }
            public Map<String, String> signatures() { return Map.of(); }
            public Map<String, String> classPackages() {
                return Map.of("Service", "sales", "OtherService", "support");
            }
            public String qualify(String className) { return className; }
            public List<CallersResult.ReflectiveCaller> reflectiveCallers(String methodName) {
                return List.of();
            }
        };

        var result = engine.callers(source, "process");

        assertEquals(CallersResult.Status.AMBIGUOUS, result.status());
        assertEquals(List.of("sales.Service.process(String)",
                "support.OtherService.process(Integer)"), result.candidates());
    }

    @Test
    void returnsDirectCallMetadataFromAnInMemoryGraph() {
        var target = new MethodReference("Service", "process", List.of(), null);
        var caller = new MethodReference("Controller", "run", List.of(), null);
        var call = new MethodCall(caller, target, 12);
        var graph = CallGraph.of(Map.of(target, Set.of(call)), Map.of(), Set.of(target, caller),
                Map.of("process", Set.of(target)));
        var source = new JsrcEngine.CallersSource() {
            public CallGraph callGraph() { return graph; }
            public Map<String, String> signatures() { return Map.of("Controller.run", "()"); }
            public Map<String, String> classPackages() { return Map.of(); }
            public String qualify(String className) { return "demo." + className; }
            public List<CallersResult.ReflectiveCaller> reflectiveCallers(String methodName) {
                return List.of();
            }
        };

        var result = engine.callers(source, "Service.process");

        assertEquals(CallersResult.Status.FOUND, result.status());
        var direct = assertInstanceOf(CallersResult.DirectCaller.class, result.callers().getFirst());
        assertEquals("demo.Controller", direct.className());
        assertEquals("()", direct.signature());
        assertEquals(com.jsrc.app.model.InvocationKind.UNKNOWN, direct.dispatch());
        assertEquals(12, direct.line());
    }
}
