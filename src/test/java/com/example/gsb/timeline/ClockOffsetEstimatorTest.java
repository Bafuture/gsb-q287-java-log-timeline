package com.example.gsb.timeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClockOffsetEstimatorTest {

    // 真实时钟：A 偏移 0（基准），B 偏移 +100ms；每跳真实时延约 10ms，maxHopDelay 取 20ms。
    private static final Duration D = Duration.ofMillis(20);

    private List<LogEntry> bidirectionalPingPong(String requestId) {
        return List.of(
                TimelineTestSupport.e("A", 1000, requestId, "a", null),   // A 发出
                TimelineTestSupport.e("B", 1110, requestId, "b", "a"),    // B 收到（真 1010）
                TimelineTestSupport.e("B", 1120, requestId, "c", "b"),    // B 回复（真 1020）
                TimelineTestSupport.e("A", 1030, requestId, "d", "c"));   // A 收回复
    }

    @Test
    void bidirectionalMarkersYieldTightBoundsAroundTrueOffset() {
        List<LogEntry> entries = bidirectionalPingPong("req-1");

        Map<String, OffsetEstimate> offsets =
                new ClockOffsetEstimator(D, "A").estimate(entries, TimelineTestSupport.graph(entries));

        OffsetEstimate a = offsets.get("A");
        assertThat(a.correctable()).isTrue();
        assertThat(a.pointMillis()).isZero();

        OffsetEstimate b = offsets.get("B");
        assertThat(b.correctable()).isTrue();
        // delta(A->B)=110 => offset(B) <= 110；delta(B->A)=-90 => offset(B) >= 90
        assertThat(b.lowerBoundMillis()).hasValue(90);
        assertThat(b.upperBoundMillis()).hasValue(110);
        // 真实偏移 +100 恰好是区间中点
        assertThat(b.pointMillis()).isEqualTo(100);
    }

    @Test
    void offsetsPropagateAcrossMultiHopCausalChains() {
        // 第一段：A <-> B（B 快 100ms）
        List<LogEntry> hop1 = bidirectionalPingPong("req-1");
        // 第二段：B <-> C（C 慢 50ms）。B 真 2000 记 2100；C 真 2010 记 1960 ...
        List<LogEntry> hop2 = List.of(
                TimelineTestSupport.e("B", 2100, "req-2", "p1", null),
                TimelineTestSupport.e("C", 1960, "req-2", "p2", "p1"),
                TimelineTestSupport.e("C", 1970, "req-2", "p3", "p2"),
                TimelineTestSupport.e("B", 2130, "req-2", "p4", "p3"));
        List<LogEntry> all = new java.util.ArrayList<>(hop1);
        all.addAll(hop2);

        Map<String, OffsetEstimate> offsets =
                new ClockOffsetEstimator(D, "A").estimate(all, TimelineTestSupport.graph(all));

        OffsetEstimate c = offsets.get("C");
        assertThat(c.correctable()).isTrue();
        assertThat(c.lowerBoundMillis()).hasValue(-70);
        assertThat(c.upperBoundMillis()).hasValue(-30);
        // 真实偏移 -50 恰好是区间中点
        assertThat(c.pointMillis()).isEqualTo(-50);
        assertThat(-50L).isBetween(c.lowerBoundMillis().orElseThrow(), c.upperBoundMillis().orElseThrow());
    }

    @Test
    void multipleNoisyMarkersAggregateByMedian() {
        // 同方向三条标记，其中两条含大噪声；中位数抗离群值
        List<LogEntry> entries = List.of(
                TimelineTestSupport.e("A", 1000, "r1", "a1", null),
                TimelineTestSupport.e("B", 1110, "r1", "b1", "a1"),
                TimelineTestSupport.e("A", 2000, "r2", "a2", null),
                TimelineTestSupport.e("B", 2500, "r2", "b2", "a2"),   // 离群
                TimelineTestSupport.e("A", 3000, "r3", "a3", null),
                TimelineTestSupport.e("B", 3108, "r3", "b3", "a3"));

        Map<String, OffsetEstimate> offsets =
                new ClockOffsetEstimator(D, "A").estimate(entries, TimelineTestSupport.graph(entries));

        // delta 中位数 = 110 => 上界 110（离群值 500 不影响）
        assertThat(offsets.get("B").upperBoundMillis()).hasValue(110);
    }

    @Test
    void sourceWithoutCausalMarkersIsUncorrectable() {
        List<LogEntry> entries = List.of(
                TimelineTestSupport.root("A", 1000, "req-1", "a1"),
                TimelineTestSupport.root("X", 5000, "req-x", "x1"));

        Map<String, OffsetEstimate> offsets =
                new ClockOffsetEstimator().estimate(entries, TimelineTestSupport.graph(entries));

        assertThat(offsets.get("A").correctable()).isTrue();
        OffsetEstimate x = offsets.get("X");
        assertThat(x.correctable()).isFalse();
        assertThat(x.pointMillis()).isZero();
        assertThat(x.lowerBoundMillis()).isEmpty();
        assertThat(x.upperBoundMillis()).isEmpty();
    }

    @Test
    void autoPicksSourceWithMostCrossMarkersAsBaseline() {
        List<LogEntry> entries = bidirectionalPingPong("req-1");

        Map<String, OffsetEstimate> offsets =
                new ClockOffsetEstimator(D, null).estimate(entries, TimelineTestSupport.graph(entries));

        assertThat(offsets.get("A").pointMillis()).isZero();
        assertThat(offsets.get("B").correctable()).isTrue();
    }

    @Test
    void contradictsMarkersThrowOnInconsistentConstraints() {
        // 标记声明 a -> b，但 b 的时钟戳早于 a 超过 maxHopDelay 允许范围，构成负环
        List<LogEntry> entries = List.of(
                TimelineTestSupport.e("A", 1000, "r1", "a1", null),
                TimelineTestSupport.e("B", 800, "r1", "b1", "a1"),
                TimelineTestSupport.e("B", 900, "r1", "b2", "b1"),
                TimelineTestSupport.e("A", 2000, "r1", "a2", "b2"));

        assertThatThrownBy(() -> new ClockOffsetEstimator(D, "A")
                .estimate(entries, TimelineTestSupport.graph(entries)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inconsistent causal constraints");
    }
}
