package ru.alfabank.analyzer.service.jenkins;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Jenkins UI/API-style URLs into root, job path segments, and optional build number.
 * Does not fetch the network; path rules follow {@code /job/segment/.../job/segment/(build)}.
 */
public final class JenkinsUrlParser {

    private JenkinsUrlParser() {
    }

    public record ParsedJenkinsUrl(
            String jenkinsRoot,
            String jobPath,
            List<String> jobSegments,
            Integer buildNumber
    ) {
        /** Multibranch-style: .../job/repo/job/branch/... — last segment is often the branch name. */
        public String branchHeuristicFromPath() {
            if (jobSegments.size() >= 2) {
                return jobSegments.get(jobSegments.size() - 1);
            }
            return null;
        }

        public boolean hasBuild() {
            return buildNumber != null;
        }

        public String jobApiUrl() {
            return jenkinsRoot + jobPath + "/api/json";
        }

        public String buildApiUrl() {
            if (buildNumber == null) {
                throw new IllegalStateException("Not a build URL.");
            }
            return jenkinsRoot + jobPath + "/" + buildNumber + "/api/json";
        }

        public String jobDisplayName() {
            return jobSegments.isEmpty() ? "" : jobSegments.get(jobSegments.size() - 1);
        }
    }

    /**
     * Strips query/fragment, console suffixes, trailing slash; requires {@code /job/...}.
     */
    public static ParsedJenkinsUrl parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("jenkinsUrl must not be blank.");
        }
        String trimmed = raw.trim();
        int hash = trimmed.indexOf('#');
        if (hash >= 0) {
            trimmed = trimmed.substring(0, hash);
        }
        int q = trimmed.indexOf('?');
        if (q >= 0) {
            trimmed = trimmed.substring(0, q);
        }
        String[] suffixes = {"/consoleText", "/console", "/log", "/pipeline-console", "/pipeline"};
        for (String suf : suffixes) {
            if (trimmed.endsWith(suf)) {
                trimmed = trimmed.substring(0, trimmed.length() - suf.length());
                break;
            }
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        URI uri = URI.create(trimmed);
        String path = uri.getPath();
        if (path == null || path.isBlank() || !path.contains("/job/")) {
            throw new IllegalArgumentException(
                    "jenkinsUrl must be a Jenkins job or build URL containing /job/..."
            );
        }

        int jobIdx = path.indexOf("/job/");
        String beforeJob = path.substring(0, jobIdx);
        String jenkinsRoot = uri.getScheme() + "://" + uri.getRawAuthority() + beforeJob;
        if (jenkinsRoot.endsWith("/")) {
            jenkinsRoot = jenkinsRoot.substring(0, jenkinsRoot.length() - 1);
        }

        String afterJob = path.substring(jobIdx + "/job/".length());
        if (afterJob.isEmpty()) {
            throw new IllegalArgumentException("Missing job path after /job/.");
        }

        String[] chunks = afterJob.split("/job/", -1);
        List<String> segments = new ArrayList<>();
        Integer buildNumber = null;

        for (int i = 0; i < chunks.length - 1; i++) {
            String chunk = chunks[i];
            if (chunk.isEmpty() || chunk.contains("/")) {
                throw new IllegalArgumentException("Malformed Jenkins job path segment: " + chunk);
            }
            segments.add(chunk);
        }

        String lastChunk = chunks[chunks.length - 1];
        if (lastChunk.isEmpty()) {
            throw new IllegalArgumentException("Empty job path segment.");
        }

        String[] lastParts = lastChunk.split("/");
        if (lastParts.length == 1) {
            String p = lastParts[0];
            if (p.matches("\\d+") && !segments.isEmpty()) {
                buildNumber = Integer.parseInt(p);
            } else {
                segments.add(p);
            }
        } else {
            String lastSeg = lastParts[lastParts.length - 1];
            if (lastSeg.matches("\\d+")) {
                buildNumber = Integer.parseInt(lastSeg);
                for (int j = 0; j < lastParts.length - 1; j++) {
                    segments.add(lastParts[j]);
                }
            } else {
                for (String p : lastParts) {
                    segments.add(p);
                }
            }
        }

        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Could not resolve Jenkins job name from URL.");
        }

        String jobPath = "/job/" + String.join("/job/", segments);
        return new ParsedJenkinsUrl(jenkinsRoot, jobPath, List.copyOf(segments), buildNumber);
    }
}
