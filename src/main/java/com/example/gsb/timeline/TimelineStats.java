package com.example.gsb.timeline;

import java.util.Map;

/**
 * Statistics about a reconstructed timeline.
 *
 * @param totalEntries          number of log entries
 * @param sourceCount           number of distinct sources
 * @param estimatedOffsets      estimated clock offset per source (millis, relative to the reference)
 * @param correctedOrderCount   number of cross-source entry pairs whose final order differs
 *                              from the naive raw-timestamp order
 * @param uncertaintyGroupCount number of timeline groups containing concurrent entries
 */
public record TimelineStats(int totalEntries,
                            int sourceCount,
                            Map<String, Long> estimatedOffsets,
                            long correctedOrderCount,
                            int uncertaintyGroupCount) {

    public TimelineStats {
        estimatedOffsets = Map.copyOf(estimatedOffsets);
    }
}
