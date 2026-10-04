package com.example.gsb.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimelineReconstructorTest {

    private static LogEntry e(long raw, String source, String requestId, Long hop, String message, long seq) {
        return new LogEntry(raw, source, requestId, hop, message, seq);
    }

    private static List<String> messageOrder(Timeline timeline) {
        return timeline.entries().stream().map(ce -> ce.entry().message()).toList();
    }

    @Test
    void reconstructsChronologicalOrderDespiteClockSkew() {
        // backend clock runs 5000 ms ahead; raw ordering puts backend entries too late? No:
        // raw order is f1(1000), f2(1500), b1(6000), b2(6500) -- b1 truly happens right after f1.
        List<LogEntry> entries = List.of(
                e(1000, "frontend", "r1", 1L, "f1", 0),
                e(6000, "backend", "r1", 2L, "b1", 0),
                e(1500, "frontend", "r2", 1L, "f2", 1),
                e(6500, "backend", "r2", 2L, "b2", 1));

        Timeline timeline = new TimelineReconstructor().reconstruct(entries);

        assertThat(messageOrder(timeline)).containsExactly("f1", "b1", "f2", "b2");
        assertThat(timeline.entries()).extracting(CorrectedEntry::correctedTimestamp)
                .containsExactly(1000L, 1000L, 1500L, 1500L);

        TimelineStats stats = timeline.stats();
        assertThat(stats.totalEntries()).isEqualTo(4);
        assertThat(stats.sourceCount()).isEqualTo(2);
        assertThat(stats.estimatedOffsets())
                .containsEntry("frontend", 0L)
                .containsEntry("backend", 5000L);
        assertThat(stats.correctedOrderCount()).isEqualTo(1); // naive raw sort places f2 before b1
        assertThat(stats.uncertaintyGroupCount()).isZero();
    }

    @Test
    void preservesCausalOrderWhenCorrectedTimestampsInvert() {
        // Two samples disagree (propagation jitter): median offset 4950 makes r2b's
        // corrected time (1950) earlier than its cause r2f (2000). Causality must win.
        List<LogEntry> entries = List.of(
                e(1000, "frontend", "r1", 1L, "r1f", 0),
                e(6000, "backend", "r1", 2L, "r1b", 0),
                e(2000, "frontend", "r2", 1L, "r2f", 1),
                e(6900, "backend", "r2", 2L, "r2b", 1));

        Timeline timeline = new TimelineReconstructor().reconstruct(entries);

        int causeIndex = messageOrder(timeline).indexOf("r2f");
        int effectIndex = messageOrder(timeline).indexOf("r2b");
        assertThat(causeIndex).isLessThan(effectIndex);

        CorrectedEntry cause = timeline.entries().get(causeIndex);
        CorrectedEntry effect = timeline.entries().get(effectIndex);
        assertThat(effect.correctedTimestamp()).isLessThan(cause.correctedTimestamp());
        assertThat(messageOrder(timeline)).containsExactly("r1f", "r1b", "r2f", "r2b");
    }

    @Test
    void groupsEntriesWithEqualCorrectedTimeAndNoCausalLinkAsConcurrent() {
        List<LogEntry> entries = List.of(
                e(1000, "svc-a", null, null, "a1", 0),
                e(1000, "svc-b", null, null, "b1", 0),
                e(2000, "svc-a", null, null, "a2", 1));

        Timeline timeline = new TimelineReconstructor().reconstruct(entries);

        assertThat(timeline.groups()).hasSize(2);
        TimelineGroup first = timeline.groups().get(0);
        assertThat(first.isConcurrent()).isTrue();
        assertThat(first.entries()).extracting(ce -> ce.entry().message()).containsExactlyInAnyOrder("a1", "b1");
        assertThat(timeline.groups().get(1).isConcurrent()).isFalse();
        assertThat(timeline.stats().uncertaintyGroupCount()).isEqualTo(1);
    }

    @Test
    void causallyLinkedEntriesWithEqualCorrectedTimeAreNotGrouped() {
        List<LogEntry> entries = List.of(
                e(1000, "frontend", "r1", 1L, "send", 0),
                e(6000, "backend", "r1", 2L, "receive", 0));

        Timeline timeline = new TimelineReconstructor().reconstruct(entries);

        assertThat(timeline.groups()).hasSize(2);
        assertThat(timeline.groups()).allMatch(group -> !group.isConcurrent());
        assertThat(timeline.stats().uncertaintyGroupCount()).isZero();
    }

    @Test
    void groupsNearSimultaneousConcurrentEntriesWithinEpsilon() {
        List<LogEntry> entries = List.of(
                e(1000, "svc-a", null, null, "a1", 0),
                e(1050, "svc-b", null, null, "b1", 0));

        Timeline strict = new TimelineReconstructor(0, null).reconstruct(entries);
        assertThat(strict.groups()).hasSize(2);

        Timeline tolerant = new TimelineReconstructor(100, null).reconstruct(entries);
        assertThat(tolerant.groups()).hasSize(1);
        assertThat(tolerant.groups().get(0).isConcurrent()).isTrue();
    }

    @Test
    void readsAndMergesMultipleLogSources() {
        LogSource frontend = new InMemoryLogSource("frontend.log", List.of(
                e(1000, "frontend", "r1", 1L, "send", 0)));
        LogSource backend = new InMemoryLogSource("backend.log", List.of(
                e(6000, "backend", "r1", 2L, "receive", 0)));

        Timeline timeline = new TimelineReconstructor().reconstruct(frontend, backend);

        assertThat(messageOrder(timeline)).containsExactly("send", "receive");
        assertThat(timeline.stats().sourceCount()).isEqualTo(2);
    }

    @Test
    void handlesEmptyInput() {
        Timeline timeline = new TimelineReconstructor().reconstruct(new ArrayList<LogEntry>());

        assertThat(timeline.groups()).isEmpty();
        assertThat(timeline.entries()).isEmpty();
        assertThat(timeline.stats().totalEntries()).isZero();
        assertThat(timeline.stats().sourceCount()).isZero();
        assertThat(timeline.stats().correctedOrderCount()).isZero();
        assertThat(timeline.stats().uncertaintyGroupCount()).isZero();
    }
}
