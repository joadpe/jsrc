package com.jsrc.app.model;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MethodIdTest {

    @Test
    void canonicalIdentityUsesErasedNormalizedParameterTypes() {
        MethodId first = new MethodId(
                TypeId.from("com.app", "Service"),
                "process",
                List.of("java.util.List<String>", "String..."));
        MethodId second = new MethodId(
                TypeId.from("com.app", "Service"),
                "process",
                List.of("java.util.List<Integer>", "String[]"));

        assertEquals(first, second);
        assertEquals("com.app.Service#process(java.util.List,String[])",
                first.canonicalName());
    }
}
