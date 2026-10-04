package com.example.gsb.timeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * 一个日志来源（通常是一台机器/一个服务）。来源内的条目按其读取顺序被视为本地真实顺序。
 */
public interface LogSource {

    String name();

    Stream<LogEntry> entries();

    /** 用内存中的条目列表构造来源，列表顺序即本地日志顺序。 */
    static LogSource of(String name, Collection<LogEntry> entries) {
        List<LogEntry> snapshot = List.copyOf(entries);
        return new LogSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Stream<LogEntry> entries() {
                return snapshot.stream();
            }
        };
    }

    /** 用行格式（见 {@link LogLineParser}）构造来源，行顺序即本地日志顺序。 */
    static LogSource ofLines(String name, Collection<String> lines) {
        return of(name, lines.stream().map(line -> LogLineParser.parse(name, line)).toList());
    }

    /** 从文件按行读取，文件行顺序即本地日志顺序。 */
    static LogSource fromPath(String name, Path path) throws IOException {
        try (Stream<String> lines = Files.lines(path)) {
            return ofLines(name, lines
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList());
        }
    }
}
