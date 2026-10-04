package com.example.gsb.timeline;

import java.time.Instant;
import java.util.List;

/**
 * 时间线输出节点：单条顺序确定的条目，或一组彼此先后无法确定的并发条目。
 */
public sealed interface TimelineNode permits TimelineNode.Ordered, TimelineNode.Concurrent {

    /**
     * @param correctedTime    校正后的点估计时间
     * @param earliestPossible 校正区间下界（可能的最早时间）
     * @param latestPossible   校正区间上界（可能的最晚时间）
     */
    record Ordered(LogEntry entry,
                   Instant correctedTime,
                   Instant earliestPossible,
                   Instant latestPossible) implements TimelineNode {
    }

    /** 组内条目互为并发（无已知因果先后且校正时间区间重叠），组内顺序不保证。 */
    record Concurrent(List<Ordered> members) implements TimelineNode {
        public Concurrent {
            members = List.copyOf(members);
            if (members.size() < 2) {
                throw new IllegalArgumentException("concurrency group must contain at least 2 entries");
            }
        }
    }

    default List<Ordered> orderedMembers() {
        if (this instanceof Ordered ordered) {
            return List.of(ordered);
        }
        return ((Concurrent) this).members();
    }
}
