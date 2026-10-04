package com.example.gsb.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CausalityGraphTest {

    @Test
    void sameSourceEntriesAreOrderedBySequence() {
        List<LogEntry> entries = List.of(
                LogEntry.of(100, "svc-a", "second", 5),
                LogEntry.of(50, "svc-a", "first", 3));

        CausalityGraph graph = CausalityGraph.build(entries);

        assertThat(graph.happensBefore(1, 0)).isTrue();
        assertThat(graph.happensBefore(0, 1)).isFalse();
        assertThat(graph.concurrent(0, 1)).isFalse();
    }

    @Test
    void requestHopsAreOrderedAcrossSourcesAndTransitively() {
        List<LogEntry> entries = List.of(
                LogEntry.of(1000, "svc-a", "r1", 1, "send", 0),
                LogEntry.of(2000, "svc-b", "r1", 2, "forward", 0),
                LogEntry.of(3000, "svc-c", "r1", 3, "receive", 0));

        CausalityGraph graph = CausalityGraph.build(entries);

        assertThat(graph.happensBefore(0, 1)).isTrue();
        assertThat(graph.happensBefore(1, 2)).isTrue();
        assertThat(graph.happensBefore(0, 2)).isTrue(); // transitive
        assertThat(graph.concurrent(0, 2)).isFalse();
    }

    @Test
    void unrelatedEntriesAreConcurrent() {
        List<LogEntry> entries = List.of(
                LogEntry.of(1000, "svc-a", "r1", 1, "a", 0),
                LogEntry.of(1000, "svc-b", "r2", 1, "b", 0),
                LogEntry.of(1000, "svc-c", "c", 0));

        CausalityGraph graph = CausalityGraph.build(entries);

        assertThat(graph.concurrent(0, 1)).isTrue();
        assertThat(graph.concurrent(0, 2)).isTrue();
        assertThat(graph.concurrent(1, 2)).isTrue();
    }

    @Test
    void sameHopEntriesOfOneRequestStayUnordered() {
        List<LogEntry> entries = List.of(
                LogEntry.of(1000, "svc-a", "r1", 1, "fan-out-1", 0),
                LogEntry.of(1000, "svc-b", "r1", 1, "fan-out-2", 0));

        CausalityGraph graph = CausalityGraph.build(entries);

        assertThat(graph.concurrent(0, 1)).isTrue();
    }
}
