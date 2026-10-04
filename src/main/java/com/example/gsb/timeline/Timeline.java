package com.example.gsb.timeline;

import java.util.List;

/**
 * 重建后的时间线：节点序列 + 统计。
 *
 * <p>节点间为确定的先后顺序；{@link TimelineNode.Concurrent} 组内的条目互为并发，
 * 不应被当作有先后关系。
 */
public final class Timeline {

    private final List<TimelineNode> nodes;
    private final TimelineStats stats;

    Timeline(List<TimelineNode> nodes, TimelineStats stats) {
        this.nodes = List.copyOf(nodes);
        this.stats = stats;
    }

    public List<TimelineNode> nodes() {
        return nodes;
    }

    /** 按输出顺序展开的全部条目（并发组内顺序不具先后语义）。 */
    public List<TimelineNode.Ordered> orderedEntries() {
        return nodes.stream().flatMap(node -> node.orderedMembers().stream()).toList();
    }

    public TimelineStats stats() {
        return stats;
    }
}
