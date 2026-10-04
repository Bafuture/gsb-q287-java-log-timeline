package com.example.gsb.timeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.example.gsb.timeline.TimelineTestSupport.e;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 局限性：与基准来源之间没有任何因果标记链的来源无法校正，
 * 其条目只能按因果约束摆放，与其余条目的先后关系标注为不确定。
 */
class LimitationTest {

    @Test
    void sourceWithoutCausalMarkersCannotBeCorrected() {
        // X 的时钟实际快了 10000ms，但它与 A 之间没有任何跨来源因果标记
        LogSource a = LogSource.of("A", List.of(e("A", 1000, "req-1", "a1", null)));
        LogSource x = LogSource.of("X", List.of(e("X", 11000, "req-x", "x1", null)));

        Timeline timeline = new TimelineReconstructor(Duration.ofMillis(20), "A")
                .reconstruct(List.of(a, x));

        TimelineStats stats = timeline.stats();
        assertThat(stats.uncorrectableSources()).containsExactly("X");
        assertThat(stats.offsetEstimates().get("X").correctable()).isFalse();
        // 无法校正 => 偏移按 0 处理，巨大的真实偏移不会被"猜"出来
        assertThat(stats.offsetEstimates().get("X").pointMillis()).isZero();
        // 条目不会丢失，但与其它来源的先后关系标注为不确定（并发组）
        assertThat(timeline.orderedEntries()).hasSize(2);
        assertThat(timeline.nodes()).hasSize(1);
        assertThat(timeline.nodes().get(0)).isInstanceOf(TimelineNode.Concurrent.class);
    }

    @Test
    void readmeDocumentsLimitationAndMinimumMarkerGuidance() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        assertThat(readme).contains("局限");
        assertThat(readme).contains("最小因果标记");
    }
}
