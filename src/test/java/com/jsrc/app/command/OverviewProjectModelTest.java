package com.jsrc.app.command;

import com.jsrc.app.command.navigate.OverviewCommand;
import com.jsrc.app.output.JsonFormatter;
import com.jsrc.app.output.JsonReader;
import com.jsrc.app.parser.HybridJavaParser;
import com.jsrc.app.project.BuildSystem;
import com.jsrc.app.project.ProjectModel;
import com.jsrc.app.project.ProjectModule;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OverviewProjectModelTest {

    @TempDir
    Path root;

    @Test
    void overviewIncludesOfflineProjectModel() {
        ProjectModule module = new ProjectModule(
                "application",
                root.resolve("application"),
                List.of(root.resolve("application/src/main/java")),
                List.of(root.resolve("application/src/test/java")),
                List.of(),
                List.of(root.resolve("application/target")),
                List.of("domain"));
        ProjectModel model = new ProjectModel(
                root, BuildSystem.MAVEN, "21", List.of(module), List.of());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CommandContext context = new CommandContext(
                List.of(), root.toString(), null,
                new JsonFormatter(false, null, new PrintStream(bytes)),
                null, new HybridJavaParser(), false, null, false, false, null, false,
                model);

        new OverviewCommand().execute(context);

        Map<?, ?> output = assertInstanceOf(
                Map.class, JsonReader.parse(bytes.toString().trim()));
        Map<?, ?> project = assertInstanceOf(Map.class, output.get("project"));
        assertEquals("maven", project.get("buildSystem"));
        assertEquals("21", project.get("javaVersion"));
        List<?> modules = assertInstanceOf(List.class, project.get("modules"));
        Map<?, ?> application = assertInstanceOf(Map.class, modules.getFirst());
        assertEquals("application", application.get("name"));
        assertEquals(List.of("application/target"), application.get("excludedRoots"));
        assertEquals(List.of("domain"), application.get("internalDependencies"));
    }
}
