package com.example.gsb.timeline;

import java.util.Map;
import java.util.Set;

/**
 * Result of clock-skew estimation.
 *
 * @param referenceSource    source whose offset is fixed to zero
 * @param offsets            estimated offset per source in millis; corrected time = raw - offset
 * @param unestimableSources sources that could not be linked to the reference by causal markers
 * @param sampleCounts       number of causal samples per directed source pair ("a->b")
 */
public record SkewEstimation(String referenceSource,
                             Map<String, Long> offsets,
                             Set<String> unestimableSources,
                             Map<String, Integer> sampleCounts) {

    public SkewEstimation {
        offsets = Map.copyOf(offsets);
        unestimableSources = Set.copyOf(unestimableSources);
        sampleCounts = Map.copyOf(sampleCounts);
    }

    public long offsetOf(String source) {
        return offsets.getOrDefault(source, 0L);
    }

    public boolean isEstimable(String source) {
        return !unestimableSources.contains(source);
    }
}
