package ru.alfabank.analyzer.model;

import java.util.List;

public record JenkinsDescribeResponse(
        String jenkinsRoot,
        List<String> jobPathSegments,
        String jobName,
        String jobFullName,
        Integer buildNumber,
        String result,
        Boolean building,
        Long timestampMillis,
        Long durationMillis,
        String branch,
        String branchSource,
        String resolvedUiUrl,
        boolean buildUrl
) {
}
