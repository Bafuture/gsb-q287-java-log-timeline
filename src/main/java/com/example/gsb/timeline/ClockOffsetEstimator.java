package com.example.gsb.timeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 基于跨来源因果标记的时钟偏移估计器。
 *
 * <p>对每条跨来源因果边 a-&gt;b（真实时延 d ∈ [0, maxHopDelay]），记录时差
 * {@code delta = t(b) - t(a) = d + offset(b) - offset(a)}，由此得到一对差约束：
 * <pre>
 *   offset(b) &lt;= offset(a) + delta            （上界，d &gt;= 0）
 *   offset(b) &gt;= offset(a) + delta - maxHopDelay （下界，d &lt;= maxHopDelay）
 * </pre>
 * 同一来源对的多条边取中位数以抗噪。所有约束构成差约束系统，以基准来源 offset = 0
 * 为锚点，用 Bellman-Ford 求每个来源的偏移区间 [lower, upper]，点估计取区间中点。
 *
 * <p>与基准来源不在同一约束连通分量中的来源标记为不可校正（offset 视为 0）。
 */
public final class ClockOffsetEstimator {

    /** 单次跨来源因果跳转（如一次 RPC）的最大假设时延。 */
    public static final Duration DEFAULT_MAX_HOP_DELAY = Duration.ofMillis(250);

    private final long maxHopDelayMillis;
    private final String baselineSource;

    public ClockOffsetEstimator() {
        this(DEFAULT_MAX_HOP_DELAY, null);
    }

    /**
     * @param maxHopDelay    单次跨来源因果跳转的最大假设时延
     * @param baselineSource 基准来源名；为 null 时自动选择跨来源因果边最多的来源
     */
    public ClockOffsetEstimator(Duration maxHopDelay, String baselineSource) {
        if (maxHopDelay.isNegative() || maxHopDelay.isZero()) {
            throw new IllegalArgumentException("maxHopDelay must be positive");
        }
        this.maxHopDelayMillis = maxHopDelay.toMillis();
        this.baselineSource = baselineSource;
    }

    public Map<String, OffsetEstimate> estimate(List<LogEntry> entries, CausalGraph graph) {
        List<String> sources = entries.stream()
                .map(LogEntry::source)
                .distinct()
                .sorted()
                .toList();
        if (sources.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> sourceIndex = new HashMap<>();
        for (int i = 0; i < sources.size(); i++) {
            sourceIndex.put(sources.get(i), i);
        }

        // 跨来源因果边 -> 按有序来源对聚合观测时差
        Map<String, Map<String, List<Long>>> deltasByPair = new LinkedHashMap<>();
        Map<String, Integer> crossDegree = new HashMap<>();
        for (CausalGraph.CausalEdge edge : graph.edges()) {
            String from = edge.from().source();
            String to = edge.to().source();
            if (from.equals(to)) {
                continue;
            }
            long delta = edge.to().timestamp().toEpochMilli() - edge.from().timestamp().toEpochMilli();
            deltasByPair.computeIfAbsent(from, k -> new LinkedHashMap<>())
                    .computeIfAbsent(to, k -> new ArrayList<>())
                    .add(delta);
            crossDegree.merge(from, 1, Integer::sum);
            crossDegree.merge(to, 1, Integer::sum);
        }

        String baseline = baselineSource != null ? baselineSource : chooseBaseline(sources, crossDegree);
        if (!sourceIndex.containsKey(baseline)) {
            throw new IllegalArgumentException("baseline source not present: " + baseline);
        }

        // 差约束图：节点 = 各来源 + 锚点 O；边 u->v 权重 w 表示 offset(v) <= offset(u) + w
        int anchor = sources.size();
        List<long[]> constraints = new ArrayList<>();
        deltasByPair.forEach((from, targets) -> targets.forEach((to, deltas) -> {
            long median = median(deltas);
            constraints.add(new long[]{sourceIndex.get(from), sourceIndex.get(to), median});
            constraints.add(new long[]{sourceIndex.get(to), sourceIndex.get(from), maxHopDelayMillis - median});
        }));
        constraints.add(new long[]{anchor, sourceIndex.get(baseline), 0});
        constraints.add(new long[]{sourceIndex.get(baseline), anchor, 0});

        int nodeCount = sources.size() + 1;
        long[] upper = bellmanFord(nodeCount, anchor, constraints, false);
        long[] toAnchor = bellmanFord(nodeCount, anchor, constraints, true);

        Map<String, OffsetEstimate> result = new TreeMap<>();
        for (String source : sources) {
            int i = sourceIndex.get(source);
            if (upper[i] == Long.MAX_VALUE || toAnchor[i] == Long.MAX_VALUE) {
                result.put(source, OffsetEstimate.uncorrectable(source));
            } else {
                long lowerBound = -toAnchor[i];
                result.put(source, OffsetEstimate.of(source, lowerBound, upper[i]));
            }
        }
        return result;
    }

    private static String chooseBaseline(List<String> sources, Map<String, Integer> crossDegree) {
        return sources.stream()
                .max(Comparator.<String>comparingInt(s -> crossDegree.getOrDefault(s, 0))
                        .thenComparing(Comparator.reverseOrder()))
                .orElseThrow();
    }

    /**
     * Bellman-Ford 最短路。{@code reverse} 为 true 时在反向图上计算，
     * 用于得到"到锚点"的距离（即偏移下界）。负环表示因果标记与 maxHopDelay 假设矛盾。
     */
    private static long[] bellmanFord(int nodeCount, int start, List<long[]> edges, boolean reverse) {
        long[] dist = new long[nodeCount];
        java.util.Arrays.fill(dist, Long.MAX_VALUE);
        dist[start] = 0;
        for (int iter = 0; iter < nodeCount; iter++) {
            boolean changed = false;
            for (long[] edge : edges) {
                int from = (int) (reverse ? edge[1] : edge[0]);
                int to = (int) (reverse ? edge[0] : edge[1]);
                long weight = edge[2];
                if (dist[from] != Long.MAX_VALUE && dist[from] + weight < dist[to]) {
                    if (iter == nodeCount - 1) {
                        throw new IllegalStateException(
                                "inconsistent causal constraints: negative cycle detected; "
                                        + "causal markers contradict the maxHopDelay assumption");
                    }
                    dist[to] = dist[from] + weight;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return dist;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }
}
