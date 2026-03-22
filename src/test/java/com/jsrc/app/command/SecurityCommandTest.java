package com.jsrc.app.command;

import com.jsrc.app.output.OutputFormatter;
import com.jsrc.app.parser.HybridJavaParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SecurityCommandTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setup() throws Exception {
        Path fixtureDir = Path.of("src/test/resources/security-fixture");
        for (File f : fixtureDir.toFile().listFiles()) {
            Files.copy(f.toPath(), tempDir.resolve(f.getName()));
        }
    }

    private String capture(Command cmd) {
        var files = new ArrayList<Path>();
        try (var stream = Files.walk(tempDir)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        } catch (Exception e) { throw new RuntimeException(e); }
        var captured = new ByteArrayOutputStream();
        var ps = new PrintStream(captured, true);
        var formatter = OutputFormatter.create(true, false, null, ps);
        var ctx = new CommandContext(files, tempDir.toString(), null, formatter, null, new HybridJavaParser());
        cmd.execute(ctx);
        ps.flush();
        return captured.toString().trim();
    }

    @Test
    void detectsSqlInjection() {
        String json = capture(new SecurityCommand("VulnerableService", false));
        assertTrue(json.contains("SQL_INJECTION"), "Should detect SQL injection. Got: " + json);
    }

    @Test
    void detectsHardcodedSecret() {
        String json = capture(new SecurityCommand("VulnerableService", false));
        assertTrue(json.contains("HARDCODED_SECRET"), "Should detect hardcoded password. Got: " + json);
    }

    @Test
    void detectsXxe() {
        String json = capture(new SecurityCommand("VulnerableService", false));
        assertTrue(json.contains("XXE"), "Should detect XXE. Got: " + json);
    }

    @Test
    void detectsInsecureDeserialization() {
        String json = capture(new SecurityCommand("VulnerableService", false));
        assertTrue(json.contains("INSECURE_DESERIALIZATION"), "Should detect insecure deserialization. Got: " + json);
    }

    @Test
    void detectsWeakCrypto() {
        String json = capture(new SecurityCommand("VulnerableService", false));
        assertTrue(json.contains("WEAK_CRYPTO"), "Should detect weak crypto (MD5). Got: " + json);
    }

    @Test
    void detectsInsecureRandom() {
        String json = capture(new SecurityCommand("VulnerableService", false));
        assertTrue(json.contains("INSECURE_RANDOM"), "Should detect insecure Random. Got: " + json);
    }

    @Test
    void noFindingsForSecureCode() {
        String json = capture(new SecurityCommand("SecureService", false));
        assertTrue(json.contains("\"totalFindings\":0"),
                "SecureService should have 0 findings. Got: " + json);
    }

    @Test
    void scanAllReturnsAggregated() {
        String json = capture(new SecurityCommand(null, true));
        assertTrue(json.contains("classesScanned"), "Should have classesScanned. Got: " + json);
        assertTrue(json.contains("bySeverity"), "Should have bySeverity. Got: " + json);
        assertTrue(json.contains("byType"), "Should have byType. Got: " + json);
    }
}
