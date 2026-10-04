package com.example.gsb.timeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.example.gsb.timeline.TimelineTestSupport.e;
import static org.assertj.core.api.Assertions.assertThat;

class TimelineReconstructorTest {

    private static final Duration D = Duration.ofMillis(20);

    @Test
    void reconstructsGlobalOrderFromSkewedClocks() {
        // B 的时钟快 100ms：真实顺序 a1(1000) b1(1010) b2(1020) a2(1030)
        LogSource a = LogSource.of("A", List.of(
                e("A", 1000, "req-1", "a1", null),
                e("A", 1030, "req-1", "a2", "b2")));
        LogSource b = LogSource.of("B", List.of(
                e("B", 1110, "req-1", "b1", "a1"),
                e("B", 1120, "req-1", "b2", "b1")));

        Timeline timeline = new TimelineReconstructor(D, "A").reconstruct(List.of(a, b));

        // 按原始时间戳排序会得到 a1 a2 b1 b2（错误）；校正后恢复真实顺序
        List<String> order = timeline.orderedEntries().stream()
                .map(o -> o.entry().spanId())
                .toList();
        assertThat(order).containsExactly("a1", "b1", "b2", "a2");
        assertThat(timeline.orderedEntries().get(1).correctedTime())
                .isEqualTo(Instant.ofEpochMilli(1010));
        assertThat(timeline.stats().correctedInversions()).isEqualTo(1);
        assertThat(timeline.stats().concurrentGroupCount()).isZero();
    }

    @Test
    void causalOrderIsNeverInvertedEvenWhenEstimateIsImprecise() {
        // B 的时钟慢 200ms：子条目 b 的原始时间戳(805)早于父条目 a(1000)
        LogSource a = LogSource.of("A", List.of(e("A", 1000, "req-1", "a", null)));
        LogSource b = LogSource.of("B", List.of(e("B", 805, "req-1", "b", "a")));

        Timeline timeline = new TimelineReconstructor(D, "A").reconstruct(List.of(a, b));

        List<String> order = timeline.orderedEntries().stream()
                .map(o -> o.entry().spanId())
                .toList();
        assertThat(order).containsExactly("a", "b");
        assertThat(timeline.stats().correctedInversions()).isEqualTo(1);
    }

    @Test
    void sameRequestEntriesKeepCausalChainAcrossThreeSources() {
        // 三个来源串联：A(0) -> B(+100) -> C(-50) -> A，真实间隔 10ms
        LogSource a = LogSource.of("A", List.of(
                e("A", 1000, "req-1", "s1", null),
                e("A", 1030, "req-1", "s4", "s3")));
        LogSource b = LogSource.of("B", List.of(e("B", 1110, "req-1", "s2", "s1")));
        LogSource c = LogSource.of("C", List.of(e("C", 970, "req-1", "s3", "s2")));

        Timeline timeline = new TimelineReconstructor(D, "A").reconstruct(List.of(a, b, c));

        List<String> order = timeline.orderedEntries().stream()
                .map(o -> o.entry().spanId())
                .toList();
        assertThat(order).containsExactly("s1", "s2", "s3", "s4");
    }
}
