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
import com.jsrc.app.command.quality.MigrateCommand;

class MigrateCommandTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setup() throws Exception {
        Path fixtureDir = Path.of("src/test/resources/migrate-fixture");
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
    void detectsDiamondOperator() {
        String json = capture(new MigrateCommand("LegacyCode", 17, false));
        assertTrue(json.contains("DIAMOND_OPERATOR"), "Should detect missing diamond. Got: " + json);
    }

    @Test
    void detectsLegacyDate() {
        String json = capture(new MigrateCommand("LegacyCode", 17, false));
        assertTrue(json.contains("DATE_LEGACY"), "Should detect legacy Date usage. Got: " + json);
    }

    @Test
    void detectsVector() {
        String json = capture(new MigrateCommand("LegacyCode", 17, false));
        assertTrue(json.contains("VECTOR_USAGE"), "Should detect Vector usage. Got: " + json);
    }

    @Test
    void detectsHashtable() {
        String json = capture(new MigrateCommand("LegacyCode", 17, false));
        assertTrue(json.contains("HASHTABLE_USAGE"), "Should detect Hashtable usage. Got: " + json);
    }

    @Test
    void detectsStringBuffer() {
        String json = capture(new MigrateCommand("LegacyCode", 17, false));
        assertTrue(json.contains("STRINGBUFFER_USAGE"), "Should detect StringBuffer. Got: " + json);
    }

    @Test
    void detectsJavaxServlet() {
        String json = capture(new MigrateCommand("LegacyCode", 17, false));
        assertTrue(json.contains("JAVAX_SERVLET"), "Should detect javax.servlet. Got: " + json);
    }

    @Test
    void detectsJavaxPersistence() {
        String json = capture(new MigrateCommand("LegacyCode", 17, false));
        assertTrue(json.contains("JAVAX_PERSISTENCE"), "Should detect javax.persistence. Got: " + json);
    }

    @Test
    void detectsTryFinallyClose() {
        String json = capture(new MigrateCommand("LegacyCode", 17, false));
        assertTrue(json.contains("TRY_WITH_RESOURCES"), "Should detect try/finally/close. Got: " + json);
    }

    @Test
    void detectsInstanceofCast() {
        String json = capture(new MigrateCommand("LegacyCode", 17, false));
        assertTrue(json.contains("INSTANCEOF_CAST"), "Should detect instanceof+cast. Got: " + json);
    }

    @Test
    void detectsFinalize() {
        String json = capture(new MigrateCommand("LegacyCode", 17, false));
        assertTrue(json.contains("FINALIZE_OVERRIDE"), "Should detect finalize(). Got: " + json);
    }

    @Test
    void respectsTargetVersion() {
        // Target Java 8 should NOT suggest instanceof pattern matching (Java 16)
        String json = capture(new MigrateCommand("LegacyCode", 8, false));
        assertFalse(json.contains("INSTANCEOF_CAST"),
                "Target 8 should not suggest Java 16 features. Got: " + json);
    }

    @Test
    void hasCategorySummary() {
        String json = capture(new MigrateCommand("LegacyCode", 17, false));
        assertTrue(json.contains("byCategory"), "Should have category summary. Got: " + json);
        assertTrue(json.contains("totalSuggestions"), "Should have total. Got: " + json);
    }
}
