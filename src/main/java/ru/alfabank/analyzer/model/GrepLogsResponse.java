package ru.alfabank.analyzer.model;

import java.util.List;

public record GrepLogsResponse(
        String jenkinsUrl,
        String query,
        boolean useRegex,
        int totalLogLines,
        int totalGrepMatches,
        int returnedGrepMatches,
        List<LogMatch> matches
) {
    public record LogMatch(
            int lineNumber,
            List<ErrorLinesResponse.ContextLine> context
    ) {
    }
}
