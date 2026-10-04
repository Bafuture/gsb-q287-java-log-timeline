package com.example.gsb.timeline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Happens-before graph over log entries, extracted from two kinds of causal markers:
 *
 * <ol>
 *   <li><b>Source order</b>: entries from the same source are ordered by their
 *       {@code sequence} (a single clock is consistent with itself).</li>
 *   <li><b>Request markers</b>: entries sharing a {@code requestId} are ordered by
 *       their {@code hopIndex} (e.g. client send &rarr; server receive).</li>
 * </ol>
 *
 * Nodes are identified by their index in the entry list passed to {@link #build(List)}.
 */
public final class CausalityGraph {

    /** A directed happens-before edge between two entry indices. */
    public record Edge(int from, int to) {
    }

    private final List<LogEntry> entries;
    private final List<Set<Integer>> successors;
    private final List<Edge> edges;
    private final Map<Integer, Set<Integer>> reachabilityCache = new HashMap<>();

    private CausalityGraph(List<LogEntry> entries, List<Set<Integer>> successors, List<Edge> edges) {
        this.entries = entries;
        this.successors = successors;
        this.edges = edges;
    }

    public static CausalityGraph build(List<LogEntry> entries) {
        int size = entries.size();
        List<Set<Integer>> successors = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            successors.add(new LinkedHashSet<>());
        }
        List<Edge> edges = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        // 1) Same-source chains, ordered by (sequence, input index).
        Map<String, List<Integer>> bySource = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            bySource.computeIfAbsent(entries.get(i).source(), k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> indices : bySource.values()) {
            indices.sort(Comparator.comparingLong((Integer i) -> entries.get(i).sequence()).thenComparingInt(i -> i));
            addChain(indices, successors, edges, seen, size);
        }

        // 2) Same-request chains, ordered by (hopIndex, input index).
        Map<String, List<Integer>> byRequest = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            LogEntry entry = entries.get(i);
            if (entry.hasCausalMarker()) {
                byRequest.computeIfAbsent(entry.requestId(), k -> new ArrayList<>()).add(i);
            }
        }
        for (List<Integer> indices : byRequest.values()) {
            indices.sort(Comparator.comparingLong((Integer i) -> entries.get(i).hopIndex()).thenComparingInt(i -> i));
            List<Integer> distinctHops = new ArrayList<>();
            for (int i : indices) {
                if (distinctHops.isEmpty()
                        || !entries.get(distinctHops.get(distinctHops.size() - 1)).hopIndex()
                            .equals(entries.get(i).hopIndex())) {
                    distinctHops.add(i);
                }
            }
            addChain(distinctHops, successors, edges, seen, size);
        }

        return new CausalityGraph(entries, successors, edges);
    }

    private static void addChain(List<Integer> chain, List<Set<Integer>> successors,
                                 List<Edge> edges, Set<Long> seen, int size) {
        for (int k = 0; k + 1 < chain.size(); k++) {
            int from = chain.get(k);
            int to = chain.get(k + 1);
            if (from == to) {
                continue;
            }
            long key = (long) from * size + to;
            if (seen.add(key)) {
                successors.get(from).add(to);
                edges.add(new Edge(from, to));
            }
        }
    }

    public int size() {
        return entries.size();
    }

    public List<Edge> edges() {
        return edges;
    }

    public Set<Integer> successorsOf(int node) {
        return successors.get(node);
    }

    /** True if {@code a} happens-before {@code b} (transitively). */
    public boolean happensBefore(int a, int b) {
        return a != b && reachableFrom(a).contains(b);
    }

    /** True if neither entry happens-before the other. */
    public boolean concurrent(int a, int b) {
        return a != b && !happensBefore(a, b) && !happensBefore(b, a);
    }

    private Set<Integer> reachableFrom(int start) {
        Set<Integer> cached = reachabilityCache.get(start);
        if (cached != null) {
            return cached;
        }
        Set<Integer> reached = new HashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            int node = stack.pop();
            for (int next : successors.get(node)) {
                if (reached.add(next)) {
                    stack.push(next);
                }
            }
        }
        reachabilityCache.put(start, reached);
        return reached;
    }
}
