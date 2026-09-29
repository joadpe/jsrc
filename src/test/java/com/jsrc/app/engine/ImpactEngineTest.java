package com.jsrc.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.jsrc.app.analysis.CallGraph;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImpactEngineTest {
    @Test
    void computesTransitiveImpactAndTestsFromAnInMemoryGraph() {
        var target = new MethodReference("Repo", "save", List.of(), null);
        var service = new MethodReference("Service", "process", List.of(), null);
        var controller = new MethodReference("Controller", "handle", List.of(), null);
        var graph = CallGraph.of(
                Map.of(target, Set.of(new MethodCall(service, target, 4)),
                        service, Set.of(new MethodCall(controller, service, 7))),
                Map.of(), Set.of(target, service, controller),
                Map.of("save", Set.of(target)));
        var source = source(graph, List.of(ClassInfo.basic("ServiceTest", "", 1, 1,
                List.of(), List.of())), 0);

        var result = assertInstanceOf(JsrcEngine.ImpactResult.Found.class,
                new JsrcEngine().impact(source, "Repo.save", true));

        assertEquals(1, result.directCallers());
        assertEquals(List.of("Service", "Controller"), result.affectedClasses());
        assertEquals(JsrcEngine.RiskLevel.LOW, result.riskLevel());
        assertEquals(List.of("ServiceTest"), result.affectedTests());
    }

    @Test
    void terminatesWhenCallersContainACycle() {
        var target = new MethodReference("Repo", "save", List.of(), null);
        var service = new MethodReference("Service", "process", List.of(), null);
        var graph = CallGraph.of(
                Map.of(target, Set.of(new MethodCall(service, target, 4)),
                        service, Set.of(new MethodCall(target, service, 7))),
                Map.of(), Set.of(target, service), Map.of("save", Set.of(target)));

        var result = assertInstanceOf(JsrcEngine.ImpactResult.Found.class,
                new JsrcEngine().impact(source(graph, List.of(), 0), "Repo.save", false));

        assertEquals(List.of("Service"), result.affectedClasses());
    }

    @Test
    void reportsTextUsagesWhenTargetIsAbsentFromInMemoryGraph() {
        var source = source(CallGraph.of(Map.of(), Map.of(), Set.of(), Map.of()),
                List.of(), 2);

        var result = assertInstanceOf(JsrcEngine.ImpactResult.Missing.class,
                new JsrcEngine().impact(source, "Ghost.run", false));

        assertEquals(2, result.textUsages());
    }

    @Test
    void preservesRawTestCountWhenTwoAffectedClassesShareASimpleName() {
        var target = new MethodReference("Repo", "save", List.of(), null);
        var sales = new MethodReference("sales.Service", "run", List.of(), null);
        var support = new MethodReference("support.Service", "run", List.of(), null);
        var graph = CallGraph.of(
                Map.of(target, Set.of(new MethodCall(sales, target, 2),
                        new MethodCall(support, target, 3))),
                Map.of(), Set.of(target, sales, support), Map.of("save", Set.of(target)));
        var source = source(graph, List.of(ClassInfo.basic("ServiceTest", "", 1, 1,
                List.of(), List.of())), 0);

        var result = assertInstanceOf(JsrcEngine.ImpactResult.Found.class,
                new JsrcEngine().impact(source, "Repo.save", true));

        assertEquals(List.of("ServiceTest"), result.affectedTests());
        assertEquals(2, result.testCount());
    }

    private static JsrcEngine.ImpactSource source(CallGraph graph,
            List<ClassInfo> classes, long usages) {
        return new JsrcEngine.ImpactSource() {
            public CallGraph callGraph() { return graph; }
            public List<ClassInfo> classes() { return classes; }
            public String qualify(String className) { return className; }
            public long textUsages(String methodName) { return usages; }
        };
    }
}
