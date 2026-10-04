package com.example.gsb.timeline;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条带时间戳、来源标识与因果标记的日志条目。
 *
 * <p>因果标记：{@code requestId} 标识同一次业务请求；{@code spanId} 标识本条记录
 * 自身；{@code parentSpanId} 指向同一请求内直接的上游记录。跨来源的 parent 链接
 * 是时钟偏差估计所依赖的主要因果信息。
 *
 * <p>{@code id} 可由读取器在读取时自动赋值（{@code 来源#序号}），仅用于稳定排序与调试。
 */
public record LogEntry(
        String id,
        String source,
        Instant timestamp,
        String requestId,
        String spanId,
        String parentSpanId,
        String message) {

    public LogEntry {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(requestId, "requestId");
        if (parentSpanId != null && parentSpanId.equals(spanId)) {
            throw new IllegalArgumentException("parentSpanId must not equal spanId for entry " + spanId);
        }
    }

    public static LogEntry of(String source, Instant timestamp, String requestId,
                              String spanId, String parentSpanId, String message) {
        return new LogEntry(null, source, timestamp, requestId, spanId, parentSpanId, message);
    }

    LogEntry assignedId(String assignedId) {
        return new LogEntry(assignedId, source, timestamp, requestId, spanId, parentSpanId, message);
    }
}
