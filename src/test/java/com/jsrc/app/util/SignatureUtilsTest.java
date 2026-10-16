package com.jsrc.app.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class SignatureUtilsTest {

    @Test
    void normalizeTypeRemovesExactlyRegexWhitespace() {
        // \s covers [ \t\n\x0B\f\r]; a no-break space is not whitespace for the regex.
        for (String type : List.of("String", " int ", "String...", "Map<String, List<Integer>>",
                "Map< K ,\tV >", "int\n[]", "Foo\u000BBar", "Foo\fBar\r", "Foo Bar",
                "java.lang. String", "T", "")) {
            assertEquals(type.trim().replace("...", "[]").replaceAll("\\s+", ""),
                    SignatureUtils.normalizeType(type), type);
        }
    }

    @Test
    void eraseTypeRemovesOnlyGenericArguments() {
        assertEquals("Map", SignatureUtils.eraseType("Map<String, List<Integer>>"));
        assertEquals("String[]", SignatureUtils.eraseType("String..."));
        assertEquals("java.util.List", SignatureUtils.eraseType(" java.util.List "));
    }

    @Test
    void typeVariablesAreSingleUppercaseAsciiLetters() {
        for (String type : List.of("T", "A", "Z", "t", "TT", "", "1", "É")) {
            assertEquals(type.matches("[A-Z]"), SignatureUtils.isTypeVariable(type), type);
        }
        assertEquals("Object", SignatureUtils.eraseParameterType("T"));
        assertEquals("TT", SignatureUtils.eraseParameterType("TT"));
    }

    @Test
    void finalModifierIsStrippedOnlyWhenFollowedByWhitespace() {
        assertEquals(List.of("String", "finalist", "int[]", "Map<K,V>"),
                SignatureUtils.extractParameterTypes("void m(final String s, finalist f, "
                        + "final\tint... values, final  Map<K, V> map)"));
    }
}
