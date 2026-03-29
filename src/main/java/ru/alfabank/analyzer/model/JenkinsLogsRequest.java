package ru.alfabank.analyzer.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record JenkinsLogsRequest(
        @NotBlank(message = "jenkinsUrl is required: Jenkins job/build URL or direct .../consoleText URL") String jenkinsUrl,
        @Min(value = 500, message = "maxLogLines must be at least 500 when set")
        @Max(value = 50_000, message = "maxLogLines must be at most 50000 when set")
        Integer maxLogLines,
        Boolean scanWholeLog
) {
    public int effectiveMaxLogLines() {
        if (Boolean.TRUE.equals(scanWholeLog)) {
            return Integer.MAX_VALUE;
        }
        return maxLogLines == null ? 12_000 : maxLogLines;
    }
}
