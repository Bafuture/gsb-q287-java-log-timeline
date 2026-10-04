package com.example.gsb.timeline;

import java.util.List;

/**
 * A named origin of log entries (a file, a service, an in-memory buffer, ...).
 */
public interface LogSource {

    String name();

    List<LogEntry> read();
}
