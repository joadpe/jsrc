package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Legacy Eclipse-style projects: no build metadata, module/src roots, Windows-1252 sources. */
class LegacyProjectIndexCliTest {

    @TempDir
    Path projectRoot;

    @Test
    void indexesWindows1252SourcesUnderDotPrefixedSourceRoots() throws Exception {
        Path sources = Files.createDirectories(projectRoot.resolve("web/src/com/example"));
        Files.write(sources.resolve("Catalogo.java"), """
                package com.example;
                /** Catálogo del año. */
                public class Catalogo { public String estado() { return "señal"; } }
                """.getBytes(Charset.forName("windows-1252")));
        Files.writeString(sources.resolve("Usuario.java"),
                "package com.example; public class Usuario {}");
        Files.writeString(projectRoot.resolve(".jsrc.yaml"), "sourceRoots:\n  - ./web/src\n");

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            exitCode = JsrcCliFactory.create().execute("--dir", projectRoot.toString(), "index");
        } finally {
            System.setErr(originalErr);
        }

        String errors = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode, errors);
        assertFalse(errors.contains("PARSE_PARTIAL"), errors);
        assertEquals(1, errors.lines()
                .filter(line -> line.startsWith("SOURCE_LEVEL_UNKNOWN")).count(), errors);
        assertTrue(errors.contains("SOURCE_LEVEL_UNKNOWN: 2 file(s)"), errors);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            JsrcCliFactory.create().execute("--dir", projectRoot.toString(), "--json", "classes");
        } finally {
            System.setOut(originalOut);
        }
        String classes = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(classes.contains("Catalogo"), classes);
        assertTrue(classes.contains("Usuario"), classes);
    }
}
