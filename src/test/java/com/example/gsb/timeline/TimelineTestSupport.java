package com.example.gsb.timeline;

import java.time.Instant;
import java.util.List;

final class TimelineTestSupport {

    private TimelineTestSupport() {
    }

    /** 以"毫秒时间戳 + requestId/spanId/parentSpanId"快速构造条目。 */
    static LogEntry e(String source, long epochMillis, String requestId,
                      String spanId, String parentSpanId) {
        return LogEntry.of(source, Instant.ofEpochMilli(epochMillis), requestId, spanId,
                parentSpanId, "message of " + spanId);
    }

    static LogEntry root(String source, long epochMillis, String requestId, String spanId) {
        return e(source, epochMillis, requestId, spanId, null);
    }

    static CausalGraph graph(List<LogEntry> entries) {
        return new CausalityExtractor().extract(entries);
    }
}
