package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BudgetContextTest {

    @Test
    @DisplayName("Should use profile default limits when not overridden")
    void shouldUseProfileDefaults() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        assertEquals(10, ctx.effectiveLimit());
        assertEquals(2048, ctx.effectiveMaxBytes());
    }

    @Test
    @DisplayName("Should override profile limits when provided")
    void shouldOverrideLimits() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, 50, 4096, false, null);
        assertEquals(50, ctx.effectiveLimit());
        assertEquals(4096, ctx.effectiveMaxBytes());
    }

    @Test
    @DisplayName("Should track degradation")
    void shouldTrackDegradation() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        ctx.setDegradedFrom("summary");
        
        Map<String, Object> meta = ctx.buildMetadata();
        assertNotNull(meta);
        assertEquals("summary", meta.get("degradedFrom"));
    }

    @Test
    @DisplayName("Should track applied transforms")
    void shouldTrackTransforms() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, null);
        ctx.addTransform("limit:10");
        ctx.addTransform("fields:tiny");
        
        Map<String, Object> meta = ctx.buildMetadata();
        assertNotNull(meta);
        assertTrue(meta.containsKey("applied"));
    }

    @Test
    @DisplayName("Should track truncation")
    void shouldTrackTruncation() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.SMALL, null, null, false, null);
        ctx.setTruncated(true);
        
        Map<String, Object> meta = ctx.buildMetadata();
        assertNotNull(meta);
        assertEquals(true, meta.get("truncated"));
    }

    @Test
    @DisplayName("Should return null metadata when noBudgetMeta is true")
    void shouldReturnNullWhenOptOut() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, true, null);
        ctx.setDegradedFrom("summary");
        
        Map<String, Object> meta = ctx.buildMetadata();
        assertNull(meta);
    }

    @Test
    @DisplayName("Should return null metadata for standard profile")
    void shouldReturnNullForStandard() {
        BudgetContext ctx = new BudgetContext(BudgetProfile.STANDARD, null, null, false, null);
        ctx.setDegradedFrom("summary");
        
        Map<String, Object> meta = ctx.buildMetadata();
        assertNull(meta);
    }

    @Test
    @DisplayName("Should create denial error object")
    void shouldCreateDenialError() {
        Map<String, Object> error = BudgetContext.createDenialError(
            "context", BudgetProfile.TINY, "jsrc mini <Class> --json");
        
        assertEquals("budget_denied", error.get("error"));
        assertEquals("context", error.get("command"));
        assertEquals("tiny", error.get("budget"));
        assertEquals("jsrc mini <Class> --json", error.get("suggestion"));
        assertTrue(error.get("hint").toString().contains("tiny"));
    }

    @Test
    @DisplayName("Should track field overrides")
    void shouldTrackFieldOverrides() {
        Set<String> fields = Set.of("name", "methodCount");
        BudgetContext ctx = new BudgetContext(BudgetProfile.TINY, null, null, false, fields);
        
        Map<String, Object> meta = ctx.buildMetadata();
        assertNotNull(meta);
        assertTrue(meta.containsKey("overrides"));
    }
}
