package com.example.gsb.timeline;

import java.util.List;

/**
 * A position on the reconstructed timeline. Groups with more than one entry are
 * <b>concurrent</b>: after clock correction their relative order could not be
 * determined, so they are reported together instead of being ordered arbitrarily.
 */
public record TimelineGroup(List<CorrectedEntry> entries) {

    public TimelineGroup {
        entries = List.copyOf(entries);
    }

    public boolean isConcurrent() {
        return entries.size() > 1;
    }
}
