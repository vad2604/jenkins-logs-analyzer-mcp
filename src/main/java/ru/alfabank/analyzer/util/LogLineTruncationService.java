package ru.alfabank.analyzer.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits how much of each log line is returned to MCP clients (large single-line blobs).
 * Max length is configurable via {@code analyzer.log-line-max-chars} or env {@code ANALYZER_LOG_LINE_MAX_CHARS}.
 */
@Component
public class LogLineTruncationService {
    private static final String SUFFIX = "... [truncated]";

    private final int maxChars;

    public LogLineTruncationService(
            @Value("${analyzer.log-line-max-chars:200}") int maxChars
    ) {
        this.maxChars = Math.max(1, maxChars);
    }

    public String forDisplay(String line) {
        if (line == null) {
            return "";
        }
        if (line.length() <= maxChars) {
            return line;
        }
        return line.substring(0, maxChars) + SUFFIX;
    }

    public int maxChars() {
        return maxChars;
    }
}
