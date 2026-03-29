package ru.alfabank.analyzer.model;

import java.util.List;

public record ErrorLinesResponse(
        String jenkinsUrl,
        int totalLogLines,
        int totalErrorMatches,
        int returnedErrorMatches,
        List<ErrorMatch> matches
) {
    public record ErrorMatch(
            int lineNumber,
            List<ContextLine> context
    ) {
    }

    public record ContextLine(
            int lineNumber,
            String line,
            boolean isErrorLine
    ) {
    }
}
