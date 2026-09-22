package com.jsrc.app.parser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TreeSitterLanguageFactoryTest {

    @Test
    @DisplayName("Should search libraries next to the running executable")
    void shouldSearchLibrariesNextToExecutable() {
        assertArrayEquals(
                new String[] {"/opt/jsrc/lib", "/home/test/lib", "/usr/local/lib", "/usr/lib", "/lib"},
                TreeSitterLanguageFactory.buildSystemLibDirs(
                        "/home/test", "", "/opt/jsrc/jsrc"));
    }

    @Test
    @DisplayName("Should preserve configured library paths before bundle path")
    void shouldPreserveConfiguredLibraryPaths() {
        assertArrayEquals(
                new String[] {
                    "/custom/one",
                    "/custom/two",
                    "/opt/jsrc/lib",
                    "/home/test/lib",
                    "/usr/local/lib",
                    "/usr/lib",
                    "/lib"
                },
                TreeSitterLanguageFactory.buildSystemLibDirs(
                        "/home/test", "/custom/one:/custom/two", "/opt/jsrc/jsrc"));
    }
}
