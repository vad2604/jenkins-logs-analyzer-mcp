package ru.alfabank.analyzer.model;

import java.util.List;

public record JenkinsLogsResponse(
        String jenkinsUrl,
        int totalLogLines,
        List<String> logLines
) {
}
