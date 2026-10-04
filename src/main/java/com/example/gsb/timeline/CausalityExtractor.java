package com.example.gsb.timeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts causal (happens-before) relations between log entries:
 *
 * <ol>
 *   <li>explicit markers: parentSpanId->spanId edges; cross-source edges of this kind
 *       are the input for clock offset estimation;</li>
 *   <li>same source, same requestId: consecutive entries in local read order are linked
 *       (on a single machine the write order of one request is the true order).</li>
 * </ol>
 *
 * <p>Entries that only share a requestId across sources without any of the markers above
 * get no edge; after correction they end up in a concurrency group, which is exactly
 * the "not enough causal markers" case.
 */
public final class CausalityExtractor {

    public CausalGraph extract(List<LogEntry> entries) {
        List<Set<Integer>> successors = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            successors.add(new LinkedHashSet<>());
        }

        Map<String, Integer> indexBySpanId = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            String spanId = entries.get(i).spanId();
            if (spanId != null) {
                indexBySpanId.putIfAbsent(spanId, i);
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            String parent = entries.get(i).parentSpanId();
            if (parent != null) {
                Integer parentIndex = indexBySpanId.get(parent);
                if (parentIndex != null && parentIndex != i) {
                    successors.get(parentIndex).add(i);
                }
            }
        }

        Map<String, Integer> lastIndexBySourceAndRequest = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            LogEntry entry = entries.get(i);
            String key = entry.source() + "\u0001" + entry.requestId();
            Integer previous = lastIndexBySourceAndRequest.put(key, i);
            if (previous != null) {
                successors.get(previous).add(i);
            }
        }

        return new CausalGraph(entries, successors);
    }
}
