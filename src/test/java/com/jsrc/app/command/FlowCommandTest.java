package com.jsrc.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jsrc.app.command.callgraph.FlowCommand;
import com.jsrc.app.index.CodebaseIndex;
import com.jsrc.app.index.IndexedCodebase;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;

class FlowCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void flowIncludesDispatchResolutionAndEvidence() throws Exception {
        Path payment = write("Payment.java", """
                package demo;
                public interface Payment {
                    void pay();
                }
                """);
        Path cardPayment = write("CardPayment.java", """
                package demo;
                public class CardPayment implements Payment {
                    public void pay() {}
                }
                """);
        Path checkout = write("Checkout.java", """
                package demo;
                public class Checkout {
                    private Payment payment;
                    public void run() { payment.pay(); }
                }
                """);
        List<Path> files = List.of(payment, cardPayment, checkout);
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);
        var output = new ByteArrayOutputStream();
        var context = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(output)), indexed, parser);

        new FlowCommand("demo.Checkout.run", 2).execute(context);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonReader.parse(output.toString().trim());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flow = (List<Map<String, Object>>) result.get("flow");
        Map<String, Object> implementationStep = flow.stream()
                .filter(step -> "demo.CardPayment.pay".equals(step.get("method")))
                .findFirst()
                .orElseThrow();
        assertEquals("interface", implementationStep.get("dispatch"));
        assertEquals("inferred", implementationStep.get("resolution"));
        @SuppressWarnings("unchecked")
        List<String> evidence = (List<String>) implementationStep.get("evidence");
        assertTrue(evidence.contains("CHA_IMPLEMENTATION"));
    }

    @Test
    void flowRejectsAmbiguousOverloads() throws Exception {
        Path service = write("Service.java", """
                package demo;
                public class Service {
                    public void run() {}
                    public void run(String value) {}
                }
                """);
        List<Path> files = List.of(service);
        var parser = new HybridJavaParser();
        var index = new CodebaseIndex();
        index.build(parser, files, tempDir, List.of());
        index.save(tempDir);
        var indexed = IndexedCodebase.tryLoad(tempDir, files);
        var output = new ByteArrayOutputStream();
        var context = new CommandContext(files, tempDir.toString(), null,
                new JsonFormatter(false, null, new PrintStream(output)), indexed, parser);

        new FlowCommand("demo.Service.run", 2).execute(context);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonReader.parse(output.toString().trim());
        assertEquals(true, result.get("ambiguous"));
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) result.get("candidates");
        assertEquals(2, candidates.size());
    }

    private Path write(String fileName, String source) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, source);
        return file;
    }
}
