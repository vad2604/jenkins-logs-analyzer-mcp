package ru.alfabank.analyzer.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ErrorLinesRequest(
        @NotBlank(message = "jenkinsUrl is required: Jenkins job/build URL or direct .../consoleText URL") String jenkinsUrl,
        @Min(value = 1, message = "maxErrorMatches must be at least 1 when set")
        @Max(value = 5000, message = "maxErrorMatches must be at most 5000 when set")
        Integer maxErrorMatches,
        @Min(value = 0, message = "contextLines must be >= 0")
        @Max(value = 20, message = "contextLines must be <= 20 to keep payloads bounded")
        Integer contextLines
) {
    public int effectiveContextLines() {
        return contextLines == null ? 2 : contextLines;
    }

    /**
     * {@code null} means no cap — return every error match (full log is always scanned).
     */
    public Integer effectiveMaxErrorMatches() {
        return maxErrorMatches;
    }
}
