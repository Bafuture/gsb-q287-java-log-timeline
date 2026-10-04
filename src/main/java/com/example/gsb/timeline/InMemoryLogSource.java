package com.example.gsb.timeline;

import java.util.List;

/**
 * A {@link LogSource} backed by an in-memory list of entries.
 */
public final class InMemoryLogSource implements LogSource {

    private final String name;
    private final List<LogEntry> entries;

    public InMemoryLogSource(String name, List<LogEntry> entries) {
        this.name = name;
        this.entries = List.copyOf(entries);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<LogEntry> read() {
        return entries;
    }
}
