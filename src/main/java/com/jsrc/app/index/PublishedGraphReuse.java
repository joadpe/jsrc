package com.jsrc.app.index;

import com.jsrc.app.analysis.CallGraph;
import java.util.List;

/**
 * Reuses a published graph only when all of its inputs are unchanged.
 * Changes to graph-building semantics must also bump the binary index version.
 */
public final class PublishedGraphReuse {

    private PublishedGraphReuse() {
    }

    public static CallGraph tryReuse(BinaryIndexV2Reader.LazyIndexData published,
                                     List<IndexEntry> previous,
                                     List<IndexEntry> current) {
        if (published == null || !sameGraphInputs(previous, current)) {
            return null;
        }
        CallGraph graph = published.ensureGraph();
        if (graph != null) {
            IndexPhaseMetrics.countPhase("index.call_graph.reused", 1);
        }
        return graph;
    }

    private static boolean sameGraphInputs(List<IndexEntry> previous,
                                            List<IndexEntry> current) {
        if (previous.size() != current.size()) return false;
        for (int index = 0; index < current.size(); index++) {
            IndexEntry oldEntry = previous.get(index);
            IndexEntry newEntry = current.get(index);
            if (!oldEntry.path().equals(newEntry.path())
                    || !oldEntry.classes().equals(newEntry.classes())
                    || !oldEntry.callEdges().equals(newEntry.callEdges())) {
                return false;
            }
        }
        return true;
    }
}
