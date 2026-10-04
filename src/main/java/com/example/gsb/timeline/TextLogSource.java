package com.example.gsb.timeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link LogSource} that parses a simple line-based text format:
 *
 * <pre>epochMillis|source|requestId|hopIndex|message</pre>
 *
 * Use {@code -} for an absent requestId or hopIndex. Blank lines and lines
 * starting with {@code #} are ignored. The entry sequence is the ordinal of
 * the parsed line, preserving per-source order.
 */
public final class TextLogSource implements LogSource {

    private final String name;
    private final List<String> lines;

    public TextLogSource(String name, List<String> lines) {
        this.name = name;
        this.lines = List.copyOf(lines);
    }

    public static TextLogSource ofPath(String name, Path path) throws IOException {
        return new TextLogSource(name, Files.readAllLines(path));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<LogEntry> read() {
        List<LogEntry> entries = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\|", 5);
            if (parts.length != 5) {
                throw new IllegalArgumentException("Malformed log line (expected 5 '|'-separated fields): " + line);
            }
            long rawTimestamp = Long.parseLong(parts[0].trim());
            String source = parts[1].trim();
            String requestId = "-".equals(parts[2].trim()) ? null : parts[2].trim();
            Long hopIndex = "-".equals(parts[3].trim()) ? null : Long.parseLong(parts[3].trim());
            String message = parts[4].trim();
            entries.add(new LogEntry(rawTimestamp, source, requestId, hopIndex, message, entries.size()));
        }
        return entries;
    }
}
