package com.example.gsb.timeline;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 时间线重建统计。
 *
 * @param entryCount             条目总数
 * @param sourceCount            来源总数
 * @param offsetEstimates        各来源的偏移估计
 * @param correctedInversions    被纠正的顺序数：原始时间戳顺序颠倒、但按因果关系被摆正的因果边数
 * @param concurrentGroupCount   不确定性（并发）组数
 * @param uncorrectableSources   因因果标记不足而无法校正的来源
 */
public record TimelineStats(
        int entryCount,
        int sourceCount,
        Map<String, OffsetEstimate> offsetEstimates,
        int correctedInversions,
        int concurrentGroupCount,
        Set<String> uncorrectableSources) {

    public TimelineStats {
        offsetEstimates = Map.copyOf(offsetEstimates);
        uncorrectableSources = Set.copyOf(uncorrectableSources);
    }

    /** 可校正来源数量。 */
    public int correctableSourceCount() {
        return sourceCount - uncorrectableSources.size();
    }

    List<String> summaryLines() {
        return List.of(
                "entries: " + entryCount,
                "sources: " + sourceCount,
                "correctable sources: " + correctableSourceCount(),
                "uncorrectable sources: " + uncorrectableSources,
                "corrected inversions: " + correctedInversions,
                "concurrent groups: " + concurrentGroupCount);
    }
}
