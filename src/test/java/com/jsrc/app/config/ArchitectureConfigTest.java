package com.jsrc.app.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchitectureConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Should parse layers from config")
    void shouldParseLayers() throws IOException {
        Files.writeString(tempDir.resolve(".jsrc.yaml"), """
                sourceRoots:
                  - src/main/java
                architecture:
                  layers:
                    - name: controller
                      pattern: "**/*Controller"
                      annotations:
                        - RestController
                        - Controller
                    - name: service
                      pattern: "**/*Service"
                      annotations:
                        - Service
                    - name: repository
                      pattern: "**/*Repository"
                      annotations:
                        - Repository
                """);

        ProjectConfig config = ProjectConfig.load(tempDir);
        assertNotNull(config);
        assertNotNull(config.architecture());
        assertEquals(3, config.architecture().layers().size());

        var controller = config.architecture().layers().getFirst();
        assertEquals("controller", controller.name());
        assertEquals("**/*Controller", controller.pattern());
        assertEquals(2, controller.annotations().size());
    }

    @Test
    @DisplayName("Should parse rules from config")
    void shouldParseRules() throws IOException {
        Files.writeString(tempDir.resolve(".jsrc.yaml"), """
                architecture:
                  rules:
                    - id: no-repo-in-controller
                      description: "Controllers must not import repositories"
                      from: controller
                      denyImport: repository
                    - id: constructor-injection
                      description: "Services must use constructor injection"
                      layer: service
                      require: constructor-injection
                      denyAnnotation: Autowired
                """);

        ProjectConfig config = ProjectConfig.load(tempDir);
        assertNotNull(config.architecture());
        assertEquals(2, config.architecture().rules().size());

        var rule1 = config.architecture().rules().getFirst();
        assertEquals("no-repo-in-controller", rule1.id());
        assertEquals("controller", rule1.from());
        assertEquals("repository", rule1.denyImport());
    }

    @Test
    @DisplayName("Should parse invokers from config")
    void shouldParseInvokers() throws IOException {
        Files.writeString(tempDir.resolve(".jsrc.yaml"), """
                architecture:
                  invokers:
                    - method: ejecutarMetodo
                      targetArg: 0
                      resolveClass: adaptadorBean
                    - method: ejecutarAccion
                      targetArg: 0
                      resolveClass: adaptadorBean
                """);

        ProjectConfig config = ProjectConfig.load(tempDir);
        assertNotNull(config.architecture());
        assertEquals(2, config.architecture().invokers().size());

        var inv = config.architecture().invokers().getFirst();
        assertEquals("ejecutarMetodo", inv.method());
        assertEquals(0, inv.targetArg());
        assertEquals("adaptadorBean", inv.resolveClass());
    }

    @Test
    @DisplayName("Should parse endpoint annotations from config")
    void shouldParseEndpoints() throws IOException {
        Files.writeString(tempDir.resolve(".jsrc.yaml"), """
                architecture:
                  endpoints:
                    - GetMapping
                    - PostMapping
                    - RequestMapping
                """);

        ProjectConfig config = ProjectConfig.load(tempDir);
        assertNotNull(config.architecture());
        assertEquals(3, config.architecture().endpointAnnotations().size());
    }

    @Test
    @DisplayName("Should handle missing architecture section")
    void shouldHandleMissingArchitecture() throws IOException {
        Files.writeString(tempDir.resolve(".jsrc.yaml"), """
                sourceRoots:
                  - src/main/java
                """);

        ProjectConfig config = ProjectConfig.load(tempDir);
        assertNotNull(config);
        assertNotNull(config.architecture());
        assertTrue(config.architecture().layers().isEmpty());
    }
}
