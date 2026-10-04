package com.example.gsb.timeline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Estimates per-source clock offsets relative to a reference source.
 *
 * <p>For a causal edge u &rarr; v where u was logged on source a at raw time tA and v on
 * source b at raw time tB, causality requires corrected(u) &le; corrected(v), i.e.
 * {@code offset(b) - offset(a) <= tB - tA}. Assuming propagation delay between adjacent
 * hops is small compared to the skew, {@code tB - tA} is a sample of the offset
 * difference. Samples per directed source pair are aggregated with the median for
 * robustness, and offsets are propagated from the reference source over the source graph
 * with BFS.</p>
 *
 * <p>Limitation: a source that has no causal-marker path to the reference source cannot
 * be corrected; it is reported in {@link SkewEstimation#unestimableSources()} and keeps
 * offset zero.</p>
 */
public final class SkewEstimator {

    /**
     * @param entries         all log entries
     * @param graph           causality graph built over the same entry list
     * @param referenceSource source to anchor at offset zero; if {@code null}, the source
     *                        with the most entries is used
     */
    public SkewEstimation estimate(List<LogEntry> entries, CausalityGraph graph, String referenceSource) {
        // Collect offset-difference samples per directed source pair.
        Map<String, List<Long>> samples = new LinkedHashMap<>();
        for (CausalityGraph.Edge edge : graph.edges()) {
            LogEntry from = entries.get(edge.from());
            LogEntry to = entries.get(edge.to());
            if (from.source().equals(to.source())) {
                continue;
            }
            samples.computeIfAbsent(from.source() + "->" + to.source(), k -> new ArrayList<>())
                    .add(to.rawTimestamp() - from.rawTimestamp());
        }

        // Source graph: edge a -> b with weight w means offset(b) ~= offset(a) + w.
        Map<String, Map<String, Long>> adjacency = new TreeMap<>();
        Map<String, Integer> sampleCounts = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> entry : samples.entrySet()) {
            String[] pair = entry.getKey().split("->", 2);
            long weight = median(entry.getValue());
            adjacency.computeIfAbsent(pair[0], k -> new TreeMap<>()).put(pair[1], weight);
            adjacency.computeIfAbsent(pair[1], k -> new TreeMap<>()).putIfAbsent(pair[0], -weight);
            sampleCounts.put(entry.getKey(), entry.getValue().size());
        }

        Set<String> allSources = new LinkedHashSet<>();
        for (LogEntry entry : entries) {
            allSources.add(entry.source());
        }
        if (allSources.isEmpty()) {
            return new SkewEstimation(referenceSource, Map.of(), Set.of(), sampleCounts);
        }
        String reference = referenceSource != null ? referenceSource : mostFrequentSource(entries);

        // BFS from the reference, accumulating offset differences.
        Map<String, Long> offsets = new LinkedHashMap<>();
        offsets.put(reference, 0L);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(reference);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (Map.Entry<String, Long> neighbor : adjacency.getOrDefault(current, Map.of()).entrySet()) {
                if (!offsets.containsKey(neighbor.getKey())) {
                    offsets.put(neighbor.getKey(), offsets.get(current) + neighbor.getValue());
                    queue.add(neighbor.getKey());
                }
            }
        }

        Set<String> unestimable = new LinkedHashSet<>();
        for (String source : allSources) {
            if (!offsets.containsKey(source)) {
                offsets.put(source, 0L);
                unestimable.add(source);
            }
        }
        return new SkewEstimation(reference, offsets, unestimable, sampleCounts);
    }

    static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        long low = sorted.get(mid - 1);
        return low + (sorted.get(mid) - low) / 2;
    }

    private static String mostFrequentSource(List<LogEntry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LogEntry entry : entries) {
            counts.merge(entry.source(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();
    }
}
