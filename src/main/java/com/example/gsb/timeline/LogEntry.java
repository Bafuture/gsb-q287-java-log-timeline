package com.example.gsb.timeline;

import java.util.Objects;

/**
 * A single log record read from one log source.
 *
 * @param rawTimestamp  timestamp reported by the source clock, epoch millis (not trustworthy across sources)
 * @param source        identifier of the machine/service that produced the entry
 * @param requestId     causal marker (e.g. correlation/request id), may be {@code null}
 * @param hopIndex      position of this entry on the request's causal chain, may be {@code null}
 * @param message       free-form payload
 * @param sequence      order of the entry inside its own source (source clocks are monotonic for the same source)
 */
public record LogEntry(long rawTimestamp,
                       String source,
                       String requestId,
                       Long hopIndex,
                       String message,
                       long sequence) {

    public LogEntry {
        Objects.requireNonNull(source, "source");
    }

    public static LogEntry of(long rawTimestamp, String source, String message, long sequence) {
        return new LogEntry(rawTimestamp, source, null, null, message, sequence);
    }

    public static LogEntry of(long rawTimestamp, String source, String requestId,
                              long hopIndex, String message, long sequence) {
        return new LogEntry(rawTimestamp, source, requestId, hopIndex, message, sequence);
    }

    public boolean hasCausalMarker() {
        return requestId != null && hopIndex != null;
    }
}
