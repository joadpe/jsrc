package com.jsrc.app.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class JsrcVersionProviderTest {

    @Test
    void readsFilteredMavenVersion() throws Exception {
        assertArrayEquals(new String[] {"jsrc 2.5.0"}, new JsrcVersionProvider().getVersion());
    }

    @Test
    void rejectsMissingVersionMetadata() {
        var provider = new JsrcVersionProvider(() -> null);

        assertThrows(IllegalStateException.class, provider::getVersion);
    }
}
