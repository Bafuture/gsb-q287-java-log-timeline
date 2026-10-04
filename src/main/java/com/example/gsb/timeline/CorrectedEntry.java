package com.example.gsb.timeline;

/**
 * A log entry together with its skew-corrected timestamp (epoch millis).
 */
public record CorrectedEntry(LogEntry entry, long correctedTimestamp) {
}
