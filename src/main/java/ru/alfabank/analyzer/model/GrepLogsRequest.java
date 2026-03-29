package ru.alfabank.analyzer.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record GrepLogsRequest(
        @NotBlank(message = "jenkinsUrl is required: Jenkins job/build URL or direct .../consoleText URL") String jenkinsUrl,
        @NotBlank(message = "query is required: substring or regex pattern (see useRegex)") String query,
        @Min(value = 0, message = "contextLines must be >= 0")
        @Max(value = 20, message = "contextLines must be <= 20 to keep payloads bounded")
        Integer contextLines,
        @Min(value = 1, message = "maxMatches must be at least 1")
        @Max(value = 5000, message = "maxMatches must be at most 5000")
        Integer maxMatches,
        Boolean ignoreCase,
        Boolean useRegex
) {
    public int effectiveContextLines() {
        return contextLines == null ? 2 : contextLines;
    }

    public int effectiveMaxMatches() {
        return maxMatches == null ? 100 : maxMatches;
    }

    public boolean effectiveIgnoreCase() {
        return ignoreCase == null || ignoreCase;
    }

    public boolean effectiveUseRegex() {
        return Boolean.TRUE.equals(useRegex);
    }
}
