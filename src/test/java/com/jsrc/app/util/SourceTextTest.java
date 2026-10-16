package com.jsrc.app.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceTextTest {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    @TempDir
    Path tempDir;

    @Test
    void decodesValidUtf8AsUtf8() {
        String source = "﻿// Configuración ñ €\nclass A {}\n";

        assertEquals(source, SourceText.decode(source.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void decodesLegacyWindowsSourcesInsteadOfFailing() {
        String source = "// Facturación del año – “señal” €\nclass A { String s = \"acción\"; }\n";

        assertEquals(source, SourceText.decode(source.getBytes(WINDOWS_1252)));
    }

    @Test
    void windows1252FallbackMatchesJdkCharsetForDefinedBytes() {
        byte[] everyByte = new byte[256];
        for (int value = 0; value < everyByte.length; value++) {
            everyByte[value] = (byte) value;
        }

        String decoded = SourceText.decode(everyByte);
        String jdk = new String(everyByte, WINDOWS_1252);

        for (int value = 0; value < everyByte.length; value++) {
            char expected = jdk.charAt(value) == '�' ? (char) value : jdk.charAt(value);
            assertEquals(expected, decoded.charAt(value), "byte 0x" + Integer.toHexString(value));
        }
    }

    @Test
    void readLinesSplitsLikeFilesReadAllLines() throws Exception {
        Path file = tempDir.resolve("Lines.java");
        Files.writeString(file, "a\r\nb\rc\n\nd\n");

        assertEquals(Files.readAllLines(file), SourceText.readLines(file));
    }
}
