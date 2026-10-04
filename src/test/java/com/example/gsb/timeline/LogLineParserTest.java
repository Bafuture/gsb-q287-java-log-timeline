package com.example.gsb.timeline;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogLineParserTest {

    @Test
    void parsesPipeSeparatedLines() {
        LogEntry entry = LogLineParser.parse("svc-a",
                "2026-10-04T10:15:30.123Z | req-7 | span-9 | span-4 | hello world");

        assertThat(entry.source()).isEqualTo("svc-a");
        assertThat(entry.timestamp()).isEqualTo(Instant.parse("2026-10-04T10:15:30.123Z"));
        assertThat(entry.requestId()).isEqualTo("req-7");
        assertThat(entry.spanId()).isEqualTo("span-9");
        assertThat(entry.parentSpanId()).isEqualTo("span-4");
        assertThat(entry.message()).isEqualTo("hello world");
    }

    @Test
    void dashMeansNoParentAndMessageMayContainPipes() {
        LogEntry entry = LogLineParser.parse("svc-a",
                "2026-10-04T10:15:30Z|req-7|span-9|-|a|b|c");

        assertThat(entry.parentSpanId()).isNull();
        assertThat(entry.message()).isEqualTo("a|b|c");
    }

    @Test
    void rejectsMalformedLines() {
        assertThatThrownBy(() -> LogLineParser.parse("svc-a", "not-a-log-line"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logSourceAssignsSequentialIdsAndSourceNames() {
        LogSource source = LogSource.ofLines("svc-b", List.of(
                "2026-10-04T10:15:30Z|req-1|s1|-|first",
                "2026-10-04T10:15:31Z|req-1|s2|s1|second"));

        Timeline timeline = new TimelineReconstructor().reconstruct(List.of(source));

        assertThat(timeline.orderedEntries()).hasSize(2);
        assertThat(timeline.orderedEntries().stream().map(o -> o.entry().id()))
                .containsExactly("svc-b#0", "svc-b#1");
        assertThat(timeline.orderedEntries().stream().map(o -> o.entry().source()))
                .containsOnly("svc-b");
    }
}
