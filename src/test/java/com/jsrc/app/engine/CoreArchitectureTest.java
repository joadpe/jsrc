package com.jsrc.app.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class CoreArchitectureTest {
    @Test
    void coreDoesNotDependOnCliPresentationOrTheEngineItServes() throws IOException {
        Path root = Path.of("src/main/java/com/jsrc/app");
        for (String corePackage : new String[] {"engine", "analysis", "index", "json", "util"}) {
            try (var sources = Files.walk(root.resolve(corePackage))) {
                for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String code = Files.readString(source);
                    for (String forbidden : new String[] {"cli", "command", "output"}) {
                        // Legacy InputValidator still delegates command-name validation
                        // to the CLI registry; that is the only allowed inverse edge.
                        if (corePackage.equals("util") && forbidden.equals("command")
                                && source.getFileName().toString().equals("InputValidator.java")) {
                            continue;
                        }
                        assertFalse(code.contains("com.jsrc.app." + forbidden + "."),
                                source + " depends on " + forbidden);
                    }
                    if (!corePackage.equals("engine")) {
                        assertFalse(code.contains("com.jsrc.app.engine."),
                                source + " creates a cycle with engine");
                    }
                    if (corePackage.equals("engine")) {
                        assertFalse(code.contains("com.jsrc.app.index."),
                                source + " depends on index representation");
                    }
                    if (corePackage.equals("analysis")
                            && !source.getFileName().toString().equals("CallGraphBuilder.java")) {
                        // CallGraphBuilder still owns the legacy analysis/index cycle.
                        assertFalse(code.contains("com.jsrc.app.index."),
                                source + " creates another cycle with index");
                    }
                }
            }
        }
    }
}
