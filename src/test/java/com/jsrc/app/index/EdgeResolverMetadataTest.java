package com.jsrc.app.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jsrc.app.project.SourceSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EdgeResolverMetadataTest {

    @Test
    void resolvingFieldMarkerPreservesSourceMetadata() {
        IndexedClass holder = new IndexedClass("Holder", "example", 1, 1,
                false, false, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(new IndexedField("service", "example.Service")));
        CallEdge marker = new CallEdge("example.Caller", "call", List.of(), 0,
                "?field:example.Holder.service", "execute", List.of(), 2, 0);
        IndexEntry original = new IndexEntry("example/Holder.java", "hash", 1L,
                SourceSet.MAIN, List.of(holder), List.of(marker), List.of(), 17);
        List<IndexEntry> entries = new ArrayList<>(List.of(original));

        new EdgeResolver().resolveMarkers(entries);

        IndexEntry resolved = entries.getFirst();
        assertEquals(SourceSet.MAIN, resolved.sourceSet());
        assertEquals(17, resolved.sourceVersion());
        assertEquals("example.Service", resolved.callEdges().getFirst().calleeClass());
    }
}
