package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

class ResolveCommandTest {

    @TempDir Path tempDir;

    @Test
    void resolvesFieldType() throws Exception {
        var result = run("Controller.service.process",
                "public class Controller {\n    private OrderService service;\n    public void handle() { service.process(); }\n}",
                "public class OrderService {\n    public void process() {}\n}");
        assertEquals("OrderService", result.get("resolvedType"));
        assertEquals("field", result.get("resolvedVia"));
    }

    @Test
    void resolvesThis() throws Exception {
        var result = run("MyClass.this.doWork",
                "public class MyClass {\n    public void doWork() {}\n}");
        assertEquals("MyClass", result.get("resolvedType"));
        assertEquals("this", result.get("resolvedVia"));
    }

    @Test
    void variableNotFound_unknown() throws Exception {
        var result = run("Controller.unknown.call",
                "public class Controller {\n    public void handle() {}\n}");
        assertEquals("unknown", result.get("resolvedType"));
        assertNotNull(result.get("error"));
    }

    @Test
    void contextClassNotFound_error() throws Exception {
        var result = run("FakeClass.service.call",
                "public class Real {\n    public void run() {}\n}");
        assertNotNull(result.get("error"));
    }

    @Test
    void includesSignatureAndReturnType() throws Exception {
        var result = run("Ctrl.svc.getName",
                "public class Ctrl {\n    private Svc svc;\n    public void go() {}\n}",
                "public class Svc {\n    public String getName() { return \"\"; }\n}");
        assertEquals("Svc", result.get("resolvedType"));
        assertNotNull(result.get("signature"));
        assertEquals("String", result.get("returnType"));
    }

    // --- helpers ---
    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String expr, String... sources) throws Exception {
        List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            String src = sources[i]; String cn = "C" + i;
            int idx = src.indexOf("class "); if (idx >= 0) cn = src.substring(idx + 6).trim().split("[\\s{<]")[0];
            Path f = tempDir.resolve(cn + ".java"); Files.writeString(f, src); files.add(f);
        }
        var parser = new HybridJavaParser(); var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of()); index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);
        var baos = new ByteArrayOutputStream();
        var ctx = new CommandContext(files, tempDir.toString(), null, new JsonFormatter(false, null, new PrintStream(baos)), indexed, parser);
        new ResolveCommand(expr).execute(ctx);
        return (Map<String, Object>) JsonReader.parse(baos.toString().trim());
    }
}
