package com.example.gsb.timeline;

import java.time.Instant;

/**
 * 最小日志行格式解析器（无外部依赖）。
 *
 * <p>格式：{@code ISO-8601时间戳 | requestId | spanId | parentSpanId | 消息}
 * 共 5 个竖线分隔字段，允许消息中包含竖线；{@code parentSpanId} 为 {@code -} 或空表示根记录。
 */
public final class LogLineParser {

    private LogLineParser() {
    }

    public static LogEntry parse(String source, String line) {
        String[] parts = line.split("\\|", 5);
        if (parts.length < 4) {
            throw new IllegalArgumentException(
                    "expected at least 4 '|'-separated fields (ts | requestId | spanId | parentSpanId [| message]): " + line);
        }
        String timestamp = parts[0].trim();
        String requestId = parts[1].trim();
        String spanId = parts[2].trim();
        String parentRaw = parts[3].trim();
        String message = parts.length == 5 ? parts[4].trim() : "";
        String parentSpanId = parentRaw.isEmpty() || "-".equals(parentRaw) ? null : parentRaw;
        if (spanId.isEmpty() || requestId.isEmpty()) {
            throw new IllegalArgumentException("requestId and spanId must not be empty: " + line);
        }
        return LogEntry.of(source, Instant.parse(timestamp), requestId, spanId, parentSpanId, message);
    }
}
