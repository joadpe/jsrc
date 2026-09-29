package com.jsrc.app.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jsrc.app.engine.CallersResult;
import com.jsrc.app.engine.JsrcEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdeProjectTest {
    @TempDir Path root;

    @BeforeEach
    void createProject() throws IOException {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>demo</artifactId><version>1</version>
                </project>
                """);
        Path sources = root.resolve("src/main/java/demo");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("Repo.java"), """
                package demo;
                class Repo {
                    void save() {}
                }
                """);
        Files.writeString(sources.resolve("Service.java"), """
                package demo;
                class Service {
                    private Repo repo = new Repo();
                    void process() {
                        // TODO review
                        repo.save();
                    }
                }
                """);
        Files.writeString(sources.resolve("Controller.java"), """
                package demo;
                class Controller {
                    private Service service = new Service();
                    void run() { service.process(); }
                }
                """);
    }

    @Test
    void searchUsesProjectDocumentsAndNavigableLocations() {
        IdeProject project = IdeProject.open(root);

        var matches = new JsrcEngine().search(project, "TODO").matches();

        assertEquals(1, matches.size());
        assertEquals("Service", matches.getFirst().className());
        assertEquals("process", matches.getFirst().methodName());
        assertTrue(matches.getFirst().inComment());
        assertEquals(5, matches.getFirst().line());
        assertTrue(Files.isRegularFile(Path.of(matches.getFirst().file())));
    }

    @Test
    void callersAndImpactUseTheSameEngineModelsAsCli() {
        IdeProject project = IdeProject.open(root);
        JsrcEngine engine = new JsrcEngine();

        CallersResult callers = engine.callers(project, "Repo.save");
        var impact = assertInstanceOf(JsrcEngine.ImpactResult.Found.class,
                engine.impact(project, "Repo.save", false));

        assertEquals(CallersResult.Status.FOUND, callers.status());
        assertEquals(1, callers.callers().size());
        assertEquals("demo.Service", callers.callers().getFirst().className());
        assertEquals("()", assertInstanceOf(CallersResult.DirectCaller.class,
                callers.callers().getFirst()).signature());
        assertEquals(1, impact.directCallers());
        assertEquals(List.of("demo.Service", "demo.Controller"),
                impact.affectedClasses());
        assertEquals(root.resolve("src/main/java/demo/Service.java"),
                project.fileForClass("demo.Service").orElseThrow());
        assertFalse(Files.exists(root.resolve(".jsrc")));
    }

    @Test
    void sourceSnapshotPreservesSearchLocationAfterDiskEdit() throws IOException {
        IdeProject project = IdeProject.open(root);
        Path file = root.resolve("src/main/java/demo/Service.java");
        var match = new JsrcEngine().search(project, "TODO").matches().getFirst();
        String original = Files.readString(file);

        Files.writeString(file, "package demo;\nclass Service {}\n");

        String snapshot = project.sourceText(file).orElseThrow();
        assertEquals(original, snapshot);
        assertTrue(snapshot.lines().skip(match.line() - 1)
                .findFirst().orElseThrow().contains("TODO review"));
    }
}
