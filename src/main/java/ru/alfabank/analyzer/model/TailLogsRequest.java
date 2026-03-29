package ru.alfabank.analyzer.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record TailLogsRequest(
        @NotBlank(message = "jenkinsUrl is required: Jenkins job/build URL or direct .../consoleText URL") String jenkinsUrl,
        @Min(value = 1, message = "tailLines must be at least 1")
        @Max(value = 10000, message = "tailLines must be at most 10000")
        Integer tailLines
) {
    public int effectiveTailLines() {
        return tailLines == null ? 500 : tailLines;
    }
}
