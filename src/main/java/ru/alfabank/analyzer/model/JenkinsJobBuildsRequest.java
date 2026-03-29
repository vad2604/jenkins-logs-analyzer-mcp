package ru.alfabank.analyzer.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record JenkinsJobBuildsRequest(
        @NotBlank(message = "jenkinsUrl is required: Jenkins job URL (or build URL — job is resolved automatically)"
        ) String jenkinsUrl,
        @Min(value = 1, message = "limit must be at least 1")
        @Max(value = 100, message = "limit must be at most 100")
        Integer limit
) {
    public int effectiveLimit() {
        return limit == null ? 20 : limit;
    }
}
