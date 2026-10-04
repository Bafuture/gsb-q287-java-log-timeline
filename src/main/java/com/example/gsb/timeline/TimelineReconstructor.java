package com.example.gsb.timeline;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 多源日志时间线重建入口。
 *
 * <p>流程：读取多来源条目 -&gt; 提取因果关系 -&gt; 估计时钟偏移 -&gt;
 * 因果约束优先的拓扑排序 -&gt; 相邻条目的并发分组 -&gt; 统计。
 */
public final class TimelineReconstructor {

    private final ClockOffsetEstimator estimator;

    public TimelineReconstructor() {
        this(new ClockOffsetEstimator());
    }

    public TimelineReconstructor(Duration maxHopDelay, String baselineSource) {
        this(new ClockOffsetEstimator(maxHopDelay, baselineSource));
    }

    public TimelineReconstructor(ClockOffsetEstimator estimator) {
        this.estimator = estimator;
    }

    public Timeline reconstruct(Collection<LogSource> sources) {
        List<LogEntry> entries = readEntries(sources);
        CausalGraph graph = new CausalityExtractor().extract(entries);
        Map<String, OffsetEstimate> offsets = estimator.estimate(entries, graph);

        List<TimelineNode.Ordered> linear = linearize(entries, graph, offsets);
        List<TimelineNode> nodes = groupConcurrent(linear, graph);
        TimelineStats stats = buildStats(entries, graph, offsets, nodes);
        return new Timeline(nodes, stats);
    }

    private static List<LogEntry> readEntries(Collection<LogSource> sources) {
        List<LogEntry> entries = new ArrayList<>();
        for (LogSource source : sources) {
            int seq = 0;
            List<LogEntry> local = source.entries().toList();
            for (LogEntry entry : local) {
                LogEntry named = new LogEntry(entry.id(), source.name(), entry.timestamp(),
                        entry.requestId(), entry.spanId(), entry.parentSpanId(), entry.message());
                String id = entry.id() != null ? entry.id() : source.name() + "#" + seq;
                entries.add(named.assignedId(id));
                seq++;
            }
        }
        return entries;
    }

    /**
     * Kahn 拓扑排序：就绪集合中始终取校正时间最早的条目。
     * 因果边决定的先后优先级高于任何时间戳，因此同一请求的顺序绝不会被颠倒。
     */
    private static List<TimelineNode.Ordered> linearize(
            List<LogEntry> entries, CausalGraph graph, Map<String, OffsetEstimate> offsets) {
        int n = entries.size();
        int[] indegree = new int[n];
        for (CausalGraph.CausalEdge edge : graph.edges()) {
            indegree[graph.indexOf(edge.to())]++;
        }
        Comparator<LogEntry> byCorrected = Comparator
                .comparing((LogEntry e) -> correctedPoint(e, offsets.get(e.source())))
                .thenComparing(LogEntry::source)
                .thenComparing(LogEntry::id);
        PriorityQueue<LogEntry> ready = new PriorityQueue<>(byCorrected);
        for (int i = 0; i < n; i++) {
            if (indegree[i] == 0) {
                ready.add(entries.get(i));
            }
        }

        List<TimelineNode.Ordered> output = new ArrayList<>(n);
        while (!ready.isEmpty()) {
            LogEntry entry = ready.poll();
            OffsetEstimate estimate = offsets.get(entry.source());
            output.add(new TimelineNode.Ordered(entry,
                    correctedPoint(entry, estimate),
                    earliestPossible(entry, estimate),
                    latestPossible(entry, estimate)));
            for (int next : graph.successorsOf(graph.indexOf(entry))) {
                if (--indegree[next] == 0) {
                    ready.add(entries.get(next));
                }
            }
        }
        if (output.size() != n) {
            throw new IllegalStateException("causal cycle detected among log entries");
        }
        return output;
    }

    /**
     * 在线性化序列上从左到右划分并发组：
     * 当前条目与组内所有成员都没有因果先后、且校正区间两两重叠时并入组；
     * 区间不重叠意味着即使偏移有不确定性，先后也已由时间区间确定，必须断开。
     */
    private static List<TimelineNode> groupConcurrent(List<TimelineNode.Ordered> linear, CausalGraph graph) {
        List<TimelineNode> nodes = new ArrayList<>();
        List<TimelineNode.Ordered> current = new ArrayList<>();
        for (TimelineNode.Ordered candidate : linear) {
            boolean joins = !current.isEmpty();
            for (TimelineNode.Ordered member : current) {
                if (!graph.isUnordered(member.entry(), candidate.entry())
                        || !intervalsOverlap(member, candidate)) {
                    joins = false;
                    break;
                }
            }
            if (joins) {
                current.add(candidate);
            } else {
                flush(nodes, current);
                current.add(candidate);
            }
        }
        flush(nodes, current);
        return nodes;
    }

    private static void flush(List<TimelineNode> nodes, List<TimelineNode.Ordered> group) {
        if (group.size() == 1) {
            nodes.add(group.get(0));
        } else if (group.size() > 1) {
            nodes.add(new TimelineNode.Concurrent(new ArrayList<>(group)));
        }
        group.clear();
    }

    private static boolean intervalsOverlap(TimelineNode.Ordered a, TimelineNode.Ordered b) {
        return !a.earliestPossible().isAfter(b.latestPossible())
                && !b.earliestPossible().isAfter(a.latestPossible());
    }

    private static TimelineStats buildStats(
            List<LogEntry> entries, CausalGraph graph,
            Map<String, OffsetEstimate> offsets, List<TimelineNode> nodes) {
        int correctedInversions = 0;
        for (CausalGraph.CausalEdge edge : graph.edges()) {
            if (edge.from().timestamp().isAfter(edge.to().timestamp())) {
                correctedInversions++;
            }
        }
        int concurrentGroups = (int) nodes.stream().filter(TimelineNode.Concurrent.class::isInstance).count();
        Set<String> sources = new HashSet<>();
        Set<String> uncorrectable = new HashSet<>();
        for (LogEntry entry : entries) {
            sources.add(entry.source());
            if (!offsets.get(entry.source()).correctable()) {
                uncorrectable.add(entry.source());
            }
        }
        return new TimelineStats(entries.size(), sources.size(), offsets,
                correctedInversions, concurrentGroups, uncorrectable);
    }

    private static Instant correctedPoint(LogEntry entry, OffsetEstimate estimate) {
        return entry.timestamp().minusMillis(estimate.pointMillis());
    }

    private static Instant earliestPossible(LogEntry entry, OffsetEstimate estimate) {
        return estimate.upperBoundMillis().isPresent()
                ? entry.timestamp().minusMillis(estimate.upperBoundMillis().getAsLong())
                : Instant.MIN;
    }

    private static Instant latestPossible(LogEntry entry, OffsetEstimate estimate) {
        return estimate.lowerBoundMillis().isPresent()
                ? entry.timestamp().minusMillis(estimate.lowerBoundMillis().getAsLong())
                : Instant.MAX;
    }
}
