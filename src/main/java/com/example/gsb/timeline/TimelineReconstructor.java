package com.example.gsb.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Rebuilds a global timeline from entries produced by multiple skewed clocks.
 *
 * <ol>
 *   <li>Extract happens-before edges ({@link CausalityGraph}).</li>
 *   <li>Estimate per-source offsets from cross-source causal edges ({@link SkewEstimator})
 *       and compute corrected timestamps.</li>
 *   <li>Order entries with a causality-constrained topological sort: among currently
 *       unblocked entries the one with the smallest corrected timestamp is picked, so a
 *       causal edge is never inverted even if corrected timestamps disagree.</li>
 *   <li>Group consecutive entries that are mutually concurrent (no causal relation either
 *       way) and whose corrected timestamps lie within {@code concurrencyEpsilonMillis}.</li>
 * </ol>
 */
public final class TimelineReconstructor {

    private final long concurrencyEpsilonMillis;
    private final String referenceSource;

    public TimelineReconstructor() {
        this(0L, null);
    }

    /**
     * @param concurrencyEpsilonMillis entries with no causal relation and corrected
     *                                 timestamps at most this far apart may be grouped
     *                                 as concurrent
     * @param referenceSource          source anchored at offset zero; {@code null} selects
     *                                 the source with the most entries
     */
    public TimelineReconstructor(long concurrencyEpsilonMillis, String referenceSource) {
        if (concurrencyEpsilonMillis < 0) {
            throw new IllegalArgumentException("concurrencyEpsilonMillis must be >= 0");
        }
        this.concurrencyEpsilonMillis = concurrencyEpsilonMillis;
        this.referenceSource = referenceSource;
    }

    public Timeline reconstruct(LogSource... sources) {
        List<LogEntry> all = new ArrayList<>();
        for (LogSource source : sources) {
            all.addAll(source.read());
        }
        return reconstruct(all);
    }

    public Timeline reconstruct(List<LogEntry> input) {
        List<LogEntry> entries = List.copyOf(input);
        CausalityGraph graph = CausalityGraph.build(entries);
        SkewEstimation estimation = new SkewEstimator().estimate(entries, graph, referenceSource);

        int size = entries.size();
        long[] corrected = new long[size];
        for (int i = 0; i < size; i++) {
            corrected[i] = entries.get(i).rawTimestamp() - estimation.offsetOf(entries.get(i).source());
        }

        List<Integer> order = topologicalOrder(entries, graph, corrected);
        List<TimelineGroup> groups = groupConcurrent(entries, graph, corrected, order);

        TimelineStats stats = buildStats(entries, estimation, groups, order, corrected);
        return new Timeline(groups, estimation, stats);
    }

    /**
     * Kahn's algorithm whose ready set is a priority queue keyed by corrected timestamp.
     * Ties break on raw timestamp, source, sequence and input index for determinism.
     */
    private List<Integer> topologicalOrder(List<LogEntry> entries, CausalityGraph graph, long[] corrected) {
        int size = entries.size();
        int[] indegree = new int[size];
        for (CausalityGraph.Edge edge : graph.edges()) {
            indegree[edge.to()]++;
        }
        Comparator<Integer> comparator = Comparator
                .comparingLong((Integer i) -> corrected[i])
                .thenComparingLong(i -> entries.get(i).rawTimestamp())
                .thenComparing(i -> entries.get(i).source())
                .thenComparingLong(i -> entries.get(i).sequence())
                .thenComparingInt(i -> i);
        PriorityQueue<Integer> ready = new PriorityQueue<>(comparator);
        for (int i = 0; i < size; i++) {
            if (indegree[i] == 0) {
                ready.add(i);
            }
        }
        List<Integer> order = new ArrayList<>(size);
        while (!ready.isEmpty()) {
            int node = ready.poll();
            order.add(node);
            for (int next : graph.successorsOf(node)) {
                if (--indegree[next] == 0) {
                    ready.add(next);
                }
            }
        }
        if (order.size() != size) {
            throw new IllegalStateException("Causal cycle detected in log entries");
        }
        return order;
    }

    private List<TimelineGroup> groupConcurrent(List<LogEntry> entries, CausalityGraph graph,
                                                long[] corrected, List<Integer> order) {
        List<TimelineGroup> groups = new ArrayList<>();
        List<CorrectedEntry> currentEntries = new ArrayList<>();
        List<Integer> currentIndices = new ArrayList<>();
        for (int index : order) {
            boolean joinsCurrent = !currentIndices.isEmpty();
            for (int member : currentIndices) {
                if (!graph.concurrent(index, member)
                        || Math.abs(corrected[index] - corrected[member]) > concurrencyEpsilonMillis) {
                    joinsCurrent = false;
                    break;
                }
            }
            if (!joinsCurrent) {
                if (!currentEntries.isEmpty()) {
                    groups.add(new TimelineGroup(currentEntries));
                }
                currentEntries = new ArrayList<>();
                currentIndices = new ArrayList<>();
            }
            currentEntries.add(new CorrectedEntry(entries.get(index), corrected[index]));
            currentIndices.add(index);
        }
        if (!currentEntries.isEmpty()) {
            groups.add(new TimelineGroup(currentEntries));
        }
        return groups;
    }

    private TimelineStats buildStats(List<LogEntry> entries, SkewEstimation estimation,
                                     List<TimelineGroup> groups, List<Integer> order, long[] corrected) {
        Set<String> sources = new LinkedHashSet<>();
        for (LogEntry entry : entries) {
            sources.add(entry.source());
        }

        // A pair is "corrected" when the final order disagrees with what a naive
        // raw-timestamp sort across different sources would have produced.
        long correctedPairs = 0;
        for (int i = 0; i < order.size(); i++) {
            int earlier = order.get(i);
            for (int j = i + 1; j < order.size(); j++) {
                int later = order.get(j);
                if (!entries.get(earlier).source().equals(entries.get(later).source())
                        && entries.get(earlier).rawTimestamp() > entries.get(later).rawTimestamp()) {
                    correctedPairs++;
                }
            }
        }

        int uncertaintyGroups = 0;
        for (TimelineGroup group : groups) {
            if (group.isConcurrent()) {
                uncertaintyGroups++;
            }
        }

        return new TimelineStats(entries.size(), sources.size(), estimation.offsets(),
                correctedPairs, uncertaintyGroups);
    }
}
