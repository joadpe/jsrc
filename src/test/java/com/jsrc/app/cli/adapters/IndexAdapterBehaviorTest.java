package com.jsrc.app.cli.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jsrc.app.cli.adapters.IndexAdapter;
import org.junit.jupiter.api.Test;

class IndexAdapterBehaviorTest {
    @Test
    void explicitIndexCommandDoesNotAutoRefreshBeforeExecuting() {
        assertEquals("index", new IndexAdapter().skipIndex());
    }
}
