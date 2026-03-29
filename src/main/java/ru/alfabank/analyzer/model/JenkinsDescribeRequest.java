package ru.alfabank.analyzer.model;

import jakarta.validation.constraints.NotBlank;

public record JenkinsDescribeRequest(
        @NotBlank(message = "jenkinsUrl is required: Jenkins job or build URL containing /job/..."
        ) String jenkinsUrl
) {
}
