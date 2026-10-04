package com.example.gsb.timeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.example.gsb.timeline.TimelineTestSupport.e;
import static org.assertj.core.api.Assertions.assertThat;

class TimelineStatsTest {

    @Test
    void reportsAllRequiredStatistics() {
        LogSource a = LogSource.of("A", List.of(
                e("A", 500, "req-0", "x1", null),
                e("A", 530, "req-0", "x4", "x3"),
                e("A", 1000, "req-1", "a", null)));
        LogSource b = LogSource.of("B", List.of(
                e("B", 610, "req-0", "x2", "x1"),
                e("B", 620, "req-0", "x3", "x2"),
                e("B", 1105, "req-1", "b", null)));

        Timeline timeline = new TimelineReconstructor(Duration.ofMillis(20), "A")
                .reconstruct(List.of(a, b));
        TimelineStats stats = timeline.stats();

        assertThat(stats.entryCount()).isEqualTo(6);
        assertThat(stats.sourceCount()).isEqualTo(2);
        assertThat(stats.offsetEstimates()).containsOnlyKeys("A", "B");
        assertThat(stats.offsetEstimates().get("B").pointMillis()).isEqualTo(100);
        // x3(B,620) -> x4(A,530) 的因果边在原始时间戳下是颠倒的，被纠正
        assertThat(stats.correctedInversions()).isEqualTo(1);
        // req-1 的 a/b 无因果标记且校正区间重叠 => 1 个并发组
        assertThat(stats.concurrentGroupCount()).isEqualTo(1);
        assertThat(stats.uncorrectableSources()).isEmpty();
        assertThat(stats.summaryLines()).anySatisfy(line -> assertThat(line).contains("entries: 6"));
    }
}
