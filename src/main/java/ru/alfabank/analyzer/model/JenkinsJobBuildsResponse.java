package ru.alfabank.analyzer.model;

import java.util.List;

public record JenkinsJobBuildsResponse(
        String jenkinsRoot,
        List<String> jobPathSegments,
        String jobName,
        String jobFullName,
        List<BuildSummary> builds
) {
    public record BuildSummary(
            int number,
            String result,
            boolean building,
            long timestampMillis,
            long durationMillis,
            String url
    ) {
    }
}
