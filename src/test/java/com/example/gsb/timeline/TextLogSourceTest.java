package com.example.gsb.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextLogSourceTest {

    @Test
    void parsesLinesIntoEntries() {
        TextLogSource source = new TextLogSource("sample.log", List.of(
                "1000|frontend|r1|1|request sent",
                "6000|backend |r1|2|request received",
                "# a comment",
                "",
                "2000|frontend|-|-|background task"));

        List<LogEntry> entries = source.read();

        assertThat(source.name()).isEqualTo("sample.log");
        assertThat(entries).hasSize(3);

        LogEntry first = entries.get(0);
        assertThat(first.rawTimestamp()).isEqualTo(1000L);
        assertThat(first.source()).isEqualTo("frontend");
        assertThat(first.requestId()).isEqualTo("r1");
        assertThat(first.hopIndex()).isEqualTo(1L);
        assertThat(first.message()).isEqualTo("request sent");
        assertThat(first.sequence()).isZero();

        LogEntry second = entries.get(1);
        assertThat(second.source()).isEqualTo("backend");

        LogEntry third = entries.get(2);
        assertThat(third.requestId()).isNull();
        assertThat(third.hopIndex()).isNull();
        assertThat(third.message()).isEqualTo("background task");
        assertThat(third.sequence()).isEqualTo(2L);
    }

    @Test
    void rejectsMalformedLine() {
        TextLogSource source = new TextLogSource("bad.log",
                List.of("1000|frontend|missing-fields"));

        assertThatThrownBy(source::read).isInstanceOf(IllegalArgumentException.class);
    }
}
