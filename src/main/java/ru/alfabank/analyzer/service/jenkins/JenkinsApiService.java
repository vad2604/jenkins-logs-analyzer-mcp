package ru.alfabank.analyzer.service.jenkins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ru.alfabank.analyzer.service.jenkins.JenkinsUrlParser.ParsedJenkinsUrl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Jenkins JSON REST API (no HTML). Uses the same env-based Basic auth as {@link ru.alfabank.analyzer.service.JenkinsLogService}.
 */
@Service
public class JenkinsApiService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record JenkinsBuildSummary(
            int number,
            String result,
            boolean building,
            long timestamp,
            long duration,
            String url
    ) {
    }

    public record JenkinsDescribeResult(
            ParsedJenkinsUrl parsed,
            String jobName,
            String jobFullName,
            Integer buildNumber,
            String result,
            Boolean building,
            Long timestamp,
            Long duration,
            String branch,
            String branchSource,
            String resolvedUiUrl,
            boolean buildUrl
    ) {
    }

    public record JenkinsJobBuildsResult(
            ParsedJenkinsUrl parsed,
            String jobName,
            String jobFullName,
            List<JenkinsBuildSummary> builds
    ) {
    }

    public JenkinsDescribeResult describe(String jenkinsUrl) throws IOException, InterruptedException {
        ParsedJenkinsUrl parsed = JenkinsUrlParser.parse(jenkinsUrl);
        if (parsed.hasBuild()) {
            String tree = "number,result,building,duration,timestamp,url,fullDisplayName,displayName,fullName,actions";
            String url = parsed.buildApiUrl() + "?tree=" + tree;
            JsonNode root = fetchJson(url);
            String apiBranch = extractBranchFromGitActions(root);
            String pathBranch = parsed.branchHeuristicFromPath();
            String branch = apiBranch != null ? apiBranch : pathBranch;
            String branchSource = apiBranch != null ? "api" : (pathBranch != null ? "path_heuristic" : null);
            String jobName = textOrNull(root.get("displayName"));
            if (jobName == null) {
                jobName = parsed.jobDisplayName();
            }
            String jobFullName = textOrNull(root.get("fullName"));
            if (jobFullName == null) {
                jobFullName = String.join("/", parsed.jobSegments());
            }
            return new JenkinsDescribeResult(
                    parsed,
                    jobName,
                    jobFullName,
                    root.has("number") ? root.get("number").asInt() : parsed.buildNumber(),
                    textOrNull(root.get("result")),
                    root.has("building") && root.get("building").asBoolean(),
                    root.has("timestamp") ? root.get("timestamp").asLong() : null,
                    root.has("duration") ? root.get("duration").asLong() : null,
                    branch,
                    branchSource,
                    stripTrailingSlash(parsed.jenkinsRoot() + parsed.jobPath() + "/" + parsed.buildNumber()),
                    true
            );
        }


        String tree = "name,fullName,displayName,url,lastBuild[number,result,building,timestamp,duration,url]";
        JsonNode root = fetchJson(parsed.jobApiUrl() + "?tree=" + tree);
        String jobFullName = textOrNull(root.get("fullName"));
        if (jobFullName == null) {
            jobFullName = String.join("/", parsed.jobSegments());
        }
        String jobName = textOrNull(root.get("name"));
        if (jobName == null) {
            jobName = parsed.jobDisplayName();
        }
        JsonNode last = root.get("lastBuild");
        Integer lastNum = null;
        String result = null;
        Boolean building = null;
        Long ts = null;
        Long dur = null;
        if (last != null && !last.isNull()) {
            lastNum = last.has("number") ? last.get("number").asInt() : null;
            result = textOrNull(last.get("result"));
            building = last.has("building") && last.get("building").asBoolean();
            ts = last.has("timestamp") ? last.get("timestamp").asLong() : null;
            dur = last.has("duration") ? last.get("duration").asLong() : null;
        }
        String branch = parsed.branchHeuristicFromPath();
        return new JenkinsDescribeResult(
                parsed,
                jobName,
                jobFullName,
                lastNum,
                result,
                building,
                ts,
                dur,
                branch,
                branch != null ? "path_heuristic" : null,
                stripTrailingSlash(parsed.jenkinsRoot() + parsed.jobPath()),
                false
        );
    }

    public JenkinsJobBuildsResult listBuilds(String jenkinsUrl, int limit) throws IOException, InterruptedException {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100.");
        }
        ParsedJenkinsUrl parsed = JenkinsUrlParser.parse(jenkinsUrl);
        // Always query job, not a single build
        String tree = "name,fullName,displayName,url,builds[number,url,result,building,timestamp,duration]{0,"
                + limit + "}";
        JsonNode root = fetchJson(parsed.jobApiUrl() + "?tree=" + tree);
        String jobFullName = textOrNull(root.get("fullName"));
        String jobName = textOrNull(root.get("name"));
        if (jobName == null) {
            jobName = parsed.jobDisplayName();
        }
        if (jobFullName == null) {
            jobFullName = String.join("/", parsed.jobSegments());
        }
        List<JenkinsBuildSummary> out = new ArrayList<>();
        JsonNode builds = root.get("builds");
        if (builds != null && builds.isArray()) {
            for (JsonNode b : builds) {
                if (b == null || !b.has("number")) {
                    continue;
                }
                out.add(new JenkinsBuildSummary(
                        b.get("number").asInt(),
                        textOrNull(b.get("result")),
                        b.has("building") && b.get("building").asBoolean(),
                        b.has("timestamp") ? b.get("timestamp").asLong() : 0L,
                        b.has("duration") ? b.get("duration").asLong() : 0L,
                        textOrNull(b.get("url"))
                ));
            }
        }
        return new JenkinsJobBuildsResult(parsed, jobName, jobFullName, out);
    }

    private static String stripTrailingSlash(String uiBase) {
        if (uiBase.endsWith("/")) {
            return uiBase.substring(0, uiBase.length() - 1);
        }
        return uiBase;
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        String t = n.asText();
        return t == null || t.isBlank() ? null : t;
    }

    private static String extractBranchFromGitActions(JsonNode buildRoot) {
        if (buildRoot.has("branch") && buildRoot.get("branch").isTextual()) {
            String b = buildRoot.get("branch").asText();
            if (b != null && !b.isBlank()) {
                return b;
            }
        }
        JsonNode actions = buildRoot.get("actions");
        if (actions != null && actions.isArray()) {
            for (JsonNode a : actions) {
                if (a == null || !a.isObject()) {
                    continue;
                }
                JsonNode rev = a.get("lastBuiltRevision");
                if (rev != null && rev.isObject()) {
                    JsonNode br = rev.get("branch");
                    if (br != null && br.isArray() && br.size() > 0) {
                        String first = br.get(0).asText();
                        if (first != null && !first.isBlank()) {
                            return first;
                        }
                    }
                }
                JsonNode revisionByBranch = a.get("revisionByBranchName");
                if (revisionByBranch != null && revisionByBranch.isObject() && revisionByBranch.size() > 0) {
                    var it = revisionByBranch.fieldNames();
                    if (it.hasNext()) {
                        return it.next();
                    }
                }
            }
        }
        JsonNode changeSet = buildRoot.get("changeSet");
        if (changeSet != null && changeSet.has("branch")) {
            JsonNode br = changeSet.get("branch");
            if (br.isTextual()) {
                return br.asText();
            }
        }
        return null;
    }

    private JsonNode fetchJson(String url) throws IOException, InterruptedException {
        String body = httpGet(url);
        return JSON.readTree(body);
    }

    private String httpGet(String url) throws IOException, InterruptedException {
        Optional<String> user = firstNonBlankEnv("JENKINS_USER", "JENKINS_USERNAME");
        Optional<String> token = firstNonBlankEnv("JENKINS_TOKEN", "JENKINS_API_TOKEN");

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();

        if (user.isPresent() && token.isPresent()) {
            String basicAuth = Base64.getEncoder().encodeToString(
                    (user.get() + ":" + token.get()).getBytes(StandardCharsets.UTF_8)
            );
            b.header("Authorization", "Basic " + basicAuth);
        }

        HttpResponse<String> response = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new IllegalStateException(jenkinsAuthHint(status, url, user.isPresent() && token.isPresent()));
        }
        if (status == 404) {
            throw new IllegalStateException(
                    "Jenkins returned HTTP 404 for " + url + ". Verify the job or build exists and the URL is correct."
            );
        }
        if (status < 200 || status >= 300) {
            String sample = shorten(response.body(), 400);
            throw new IllegalStateException(
                    "Jenkins API request failed, HTTP " + status + ", url=" + url
                            + (sample.isEmpty() ? "" : ", responseSample=" + sample)
            );
        }
        return response.body();
    }

    private static Optional<String> firstNonBlankEnv(String... names) {
        for (String name : names) {
            String v = System.getenv(name);
            if (v != null && !v.isBlank()) {
                return Optional.of(v.trim());
            }
        }
        return Optional.empty();
    }

    private static String jenkinsAuthHint(int status, String requestUrl, boolean credentialsProvided) {
        String base = "Jenkins returned HTTP " + status + " for " + requestUrl + ". ";
        if (!credentialsProvided) {
            return base
                    + "No credentials were sent. Set environment variables JENKINS_USER (or JENKINS_USERNAME) "
                    + "and JENKINS_TOKEN (or JENKINS_API_TOKEN), then restart the MCP server process so it picks them up.";
        }
        return base
                + "Credentials were sent via Basic auth from environment variables, but Jenkins rejected them. "
                + "Confirm the user can access this job and the token is a valid Jenkins API token.";
    }

    private static String shorten(String s, int maxLen) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String t = s.strip().replace('\n', ' ');
        return t.length() <= maxLen ? t : t.substring(0, maxLen) + "...";
    }
}
