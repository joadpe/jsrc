package com.jsrc.app.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jsrc.app.cli.JsrcCliFactory;
import com.jsrc.app.index.CodebaseIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Java22ProjectIndexTest {

    @Test
    void indexesMavenProjectDeclaringJavaTwentyTwo(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>self-index</artifactId><version>1</version>
                  <properties><maven.compiler.release>22</maven.compiler.release></properties>
                </project>
                """);
        Path sourceRoot = Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Example.java"),
                "class Example { int revision() { return 1; } }\n");

        assertEquals(0, JsrcCliFactory.create().execute("--dir", root.toString(), "index"));
        var entries = CodebaseIndex.loadPublished(root);
        assertEquals(1, entries.size());
        assertEquals(22, entries.getFirst().sourceVersion());
        assertEquals("Example", entries.getFirst().classes().getFirst().name());
    }
}
