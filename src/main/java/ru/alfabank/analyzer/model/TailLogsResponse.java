package ru.alfabank.analyzer.model;

import java.util.List;

public record TailLogsResponse(
        String jenkinsUrl,
        int totalLogLines,
        int returnedLines,
        List<String> logTail
) {
}
