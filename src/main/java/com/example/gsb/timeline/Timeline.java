package com.example.gsb.timeline;

import java.util.List;

/**
 * The reconstructed timeline: an ordered list of groups (singletons or concurrent
 * sets), the skew estimation used, and aggregate statistics.
 */
public record Timeline(List<TimelineGroup> groups,
                       SkewEstimation skewEstimation,
                       TimelineStats stats) {

    public Timeline {
        groups = List.copyOf(groups);
    }

    /** All entries in timeline order (concurrent entries share their group's position). */
    public List<CorrectedEntry> entries() {
        return groups.stream().flatMap(group -> group.entries().stream()).toList();
    }
}
