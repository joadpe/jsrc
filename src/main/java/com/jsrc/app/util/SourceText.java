package com.jsrc.app.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Decodes Java source text. Valid UTF-8 is decoded as UTF-8; anything else is
 * decoded as Windows-1252, the usual encoding of legacy sources edited on Windows.
 */
public final class SourceText {

    /**
     * Windows-1252 characters for bytes 0x80-0x9F. Undefined bytes keep their
     * ISO-8859-1 control character, as in the WHATWG windows-1252 index. A table
     * avoids depending on charsets that native images may omit.
     */
    private static final char[] WINDOWS_1252_0X80 = (
            "€\u0081‚ƒ„…†‡"
            + "ˆ‰Š‹Œ\u008DŽ\u008F"
            + "\u0090‘’“”•–—"
            + "˜™š›œ\u009DžŸ").toCharArray();

    private SourceText() {}

    /** Reads and decodes a source file. */
    public static String read(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    /** Reads a source file split with the same line terminators as {@link Files#readAllLines}. */
    public static List<String> readLines(Path file) throws IOException {
        return read(file).lines().toList();
    }

    /** Decodes source bytes as UTF-8, or as Windows-1252 when they are not valid UTF-8. */
    public static String decode(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException notUtf8) {
            return decodeWindows1252(content);
        }
    }

    /** Whether the bytes are valid UTF-8, which {@link #decode} decodes as UTF-8. */
    public static boolean isUtf8(byte[] content) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException notUtf8) {
            return false;
        }
    }

    private static String decodeWindows1252(byte[] content) {
        char[] chars = new char[content.length];
        for (int i = 0; i < content.length; i++) {
            int value = content[i] & 0xFF;
            chars[i] = value >= 0x80 && value < 0xA0
                    ? WINDOWS_1252_0X80[value - 0x80]
                    : (char) value;
        }
        return new String(chars);
    }
}
