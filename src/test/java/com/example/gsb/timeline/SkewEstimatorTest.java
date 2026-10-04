package com.example.gsb.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SkewEstimatorTest {

    private static LogEntry e(long raw, String source, String requestId, long hop, long seq) {
        return LogEntry.of(raw, source, requestId, hop, source + "@" + raw, seq);
    }

    private static SkewEstimation estimate(List<LogEntry> entries, String reference) {
        return new SkewEstimator().estimate(entries, CausalityGraph.build(entries), reference);
    }

    @Test
    void estimatesConstantOffsetFromCausalMarkers() {
        // backend clock runs 5000 ms ahead of frontend.
        List<LogEntry> entries = List.of(
                e(1000, "frontend", "r1", 1, 0),
                e(6000, "backend", "r1", 2, 0),
                e(2000, "frontend", "r2", 1, 1),
                e(7000, "backend", "r2", 2, 1));

        SkewEstimation estimation = estimate(entries, "frontend");

        assertThat(estimation.offsetOf("frontend")).isZero();
        assertThat(estimation.offsetOf("backend")).isEqualTo(5000);
        assertThat(estimation.referenceSource()).isEqualTo("frontend");
        assertThat(estimation.unestimableSources()).isEmpty();
        assertThat(estimation.sampleCounts()).containsEntry("frontend->backend", 2);
    }

    @Test
    void medianResistsOutlierSamples() {
        // Last pair has a 4000 ms propagation delay outlier; median still yields 5000.
        List<LogEntry> entries = List.of(
                e(1000, "frontend", "r1", 1, 0),
                e(6000, "backend", "r1", 2, 0),
                e(2000, "frontend", "r2", 1, 1),
                e(7000, "backend", "r2", 2, 1),
                e(3000, "frontend", "r3", 1, 2),
                e(8000, "backend", "r3", 2, 2),
                e(4000, "frontend", "r4", 1, 3),
                e(13000, "backend", "r4", 2, 3));

        SkewEstimation estimation = estimate(entries, "frontend");

        assertThat(estimation.offsetOf("backend")).isEqualTo(5000);
    }

    @Test
    void propagatesOffsetsAcrossChainedSources() {
        // frontend -> backend (+5000) -> db (+3000 more).
        List<LogEntry> entries = List.of(
                e(1000, "frontend", "r1", 1, 0),
                e(6000, "backend", "r1", 2, 0),
                e(9000, "db", "r1", 3, 0));

        SkewEstimation estimation = estimate(entries, "frontend");

        assertThat(estimation.offsetOf("backend")).isEqualTo(5000);
        assertThat(estimation.offsetOf("db")).isEqualTo(8000);
    }

    @Test
    void sourcesWithoutCausalMarkersCannotBeEstimated() {
        // Limitation: "worker" never appears in any request, so its clock cannot be corrected.
        List<LogEntry> entries = List.of(
                e(1000, "frontend", "r1", 1, 0),
                e(6000, "backend", "r1", 2, 0),
                LogEntry.of(500, "worker", "background tick", 0),
                LogEntry.of(700, "worker", "background tock", 1));

        SkewEstimation estimation = estimate(entries, "frontend");

        assertThat(estimation.isEstimable("backend")).isTrue();
        assertThat(estimation.isEstimable("worker")).isFalse();
        assertThat(estimation.unestimableSources()).containsExactly("worker");
        assertThat(estimation.offsetOf("worker")).isZero();
    }

    @Test
    void picksSourceWithMostEntriesAsReferenceWhenUnspecified() {
        List<LogEntry> entries = List.of(
                e(1000, "frontend", "r1", 1, 0),
                e(6000, "backend", "r1", 2, 0),
                e(2000, "frontend", "r2", 1, 1),
                e(7000, "backend", "r2", 2, 1),
                LogEntry.of(3000, "frontend", "health check", 2));

        SkewEstimation estimation = estimate(entries, null);

        assertThat(estimation.referenceSource()).isEqualTo("frontend");
        assertThat(estimation.offsetOf("backend")).isEqualTo(5000);
    }

    @Test
    void explicitReferenceSourceIsAnchoredAtZero() {
        List<LogEntry> entries = List.of(
                e(1000, "frontend", "r1", 1, 0),
                e(6000, "backend", "r1", 2, 0));

        SkewEstimation estimation = estimate(entries, "backend");

        assertThat(estimation.offsetOf("backend")).isZero();
        assertThat(estimation.offsetOf("frontend")).isEqualTo(-5000);
    }
}
