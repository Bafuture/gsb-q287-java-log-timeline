package com.example.gsb.timeline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 日志条目间的有向因果图。边 a -&gt; b 表示 a 必然发生在 b 之前（happens-before）。
 * 提供传递闭包查询，用于约束排序与并发判定。
 */
public final class CausalGraph {

    /** 一条因果边：{@code from} 必然先于 {@code to}。 */
    public record CausalEdge(LogEntry from, LogEntry to) {
    }

    private final List<LogEntry> entries;
    private final Map<LogEntry, Integer> indexByEntry;
    private final List<Set<Integer>> successors;
    private final List<CausalEdge> edges;
    private volatile List<BitSet> closure;

    CausalGraph(List<LogEntry> entries, List<Set<Integer>> successors) {
        this.entries = List.copyOf(entries);
        this.successors = new ArrayList<>(successors.size());
        this.edges = new ArrayList<>();
        this.indexByEntry = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            this.indexByEntry.put(entries.get(i), i);
            Set<Integer> targets = Set.copyOf(successors.get(i));
            this.successors.add(targets);
            for (int target : targets) {
                this.edges.add(new CausalEdge(entries.get(i), entries.get(target)));
            }
        }
    }

    public List<LogEntry> entries() {
        return entries;
    }

    public List<CausalEdge> edges() {
        return edges;
    }

    /** a 是否（直接或间接）必然先于 b。 */
    public boolean isBefore(LogEntry a, LogEntry b) {
        Integer from = indexByEntry.get(a);
        Integer to = indexByEntry.get(b);
        if (from == null || to == null) {
            throw new IllegalArgumentException("entry not part of this graph");
        }
        return closure().get(from).get(to);
    }

    /** a 与 b 之间是否没有任何已知的先后约束。 */
    public boolean isUnordered(LogEntry a, LogEntry b) {
        return !isBefore(a, b) && !isBefore(b, a);
    }

    Set<Integer> successorsOf(int index) {
        return successors.get(index);
    }

    int indexOf(LogEntry entry) {
        Integer index = indexByEntry.get(entry);
        if (index == null) {
            throw new IllegalArgumentException("entry not part of this graph");
        }
        return index;
    }

    private List<BitSet> closure() {
        List<BitSet> result = closure;
        if (result == null) {
            result = computeClosure();
            closure = result;
        }
        return result;
    }

    private List<BitSet> computeClosure() {
        List<BitSet> reach = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            reach.add(new BitSet(entries.size()));
        }
        for (int start = 0; start < entries.size(); start++) {
            BitSet seen = reach.get(start);
            ArrayDeque<Integer> stack = new ArrayDeque<>(successors.get(start));
            while (!stack.isEmpty()) {
                int node = stack.pop();
                if (seen.get(node)) {
                    continue;
                }
                seen.set(node);
                stack.addAll(successors.get(node));
            }
        }
        return reach;
    }
}
