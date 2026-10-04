package com.example.gsb.timeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.example.gsb.timeline.TimelineTestSupport.e;
import static org.assertj.core.api.Assertions.assertThat;

class UncertaintyAnnotationTest {

    private static final Duration D = Duration.ofMillis(20);

    /** req-0 的往返标记把 B 的偏移约束在 [90, 110]（真实 +100）。 */
    private static List<LogEntry> markers() {
        return List.of(
                e("A", 500, "req-0", "x1", null),
                e("B", 610, "req-0", "x2", "x1"),
                e("B", 620, "req-0", "x3", "x2"),
                e("A", 530, "req-0", "x4", "x3"));
    }

    @Test
    void overlappingIncomparableEntriesAreGroupedAsConcurrent() {
        List<LogEntry> entries = new java.util.ArrayList<>(markers());
        // 两个互相独立的请求，校正区间 [1000,1000] 与 [995,1015] 重叠
        entries.add(e("A", 1000, "req-1", "a", null));
        entries.add(e("B", 1105, "req-2", "b", null));

        Timeline timeline = new TimelineReconstructor(D, "A")
                .reconstruct(List.of(LogSource.of("A", entries.stream().filter(x -> x.source().equals("A")).toList()),
                        LogSource.of("B", entries.stream().filter(x -> x.source().equals("B")).toList())));

        TimelineNode last = timeline.nodes().get(timeline.nodes().size() - 1);
        assertThat(last).isInstanceOf(TimelineNode.Concurrent.class);
        TimelineNode.Concurrent group = (TimelineNode.Concurrent) last;
        assertThat(group.members()).hasSize(2);
        assertThat(group.members().stream().map(o -> o.entry().spanId()))
                .containsExactlyInAnyOrder("a", "b");
        assertThat(timeline.stats().concurrentGroupCount()).isEqualTo(1);
    }

    @Test
    void disjointIntervalsAreNotGrouped() {
        List<LogEntry> entries = new java.util.ArrayList<>(markers());
        // b 的校正区间 [1090,1110] 与 a 的 [1000,1000] 不重叠：先后确定
        entries.add(e("A", 1000, "req-1", "a", null));
        entries.add(e("B", 1200, "req-2", "b", null));

        Timeline timeline = new TimelineReconstructor(D, "A")
                .reconstruct(List.of(LogSource.of("A", entries.stream().filter(x -> x.source().equals("A")).toList()),
                        LogSource.of("B", entries.stream().filter(x -> x.source().equals("B")).toList())));

        assertThat(timeline.stats().concurrentGroupCount()).isZero();
        List<String> tail = timeline.orderedEntries().stream()
                .map(o -> o.entry().spanId())
                .toList();
        assertThat(tail).endsWith("a", "b");
    }

    @Test
    void sameRequestWithoutExplicitMarkersStaysConcurrent() {
        // 同一 requestId 出现在两个来源，但没有任何 parentSpanId 标记：
        // 无法建立因果边，校正后必须标注为并发而不是按时间戳猜顺序
        LogSource a = LogSource.of("A", List.of(
                e("A", 500, "req-0", "x1", null),
                e("A", 530, "req-0", "x4", "x3"),
                e("A", 1000, "req-9", "a", null)));
        LogSource b = LogSource.of("B", List.of(
                e("B", 610, "req-0", "x2", "x1"),
                e("B", 620, "req-0", "x3", "x2"),
                e("B", 1105, "req-9", "b", null)));

        Timeline timeline = new TimelineReconstructor(D, "A").reconstruct(List.of(a, b));

        TimelineNode last = timeline.nodes().get(timeline.nodes().size() - 1);
        assertThat(last).isInstanceOf(TimelineNode.Concurrent.class);
        assertThat(((TimelineNode.Concurrent) last).members().stream()
                .map(o -> o.entry().spanId()))
                .containsExactlyInAnyOrder("a", "b");
    }
}
