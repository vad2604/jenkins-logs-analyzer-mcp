package ru.alfabank.analyzer.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import ru.alfabank.analyzer.model.ErrorLinesRequest;
import ru.alfabank.analyzer.model.ErrorLinesResponse;
import ru.alfabank.analyzer.model.GrepLogsRequest;
import ru.alfabank.analyzer.model.GrepLogsResponse;
import ru.alfabank.analyzer.model.JenkinsDescribeRequest;
import ru.alfabank.analyzer.model.JenkinsDescribeResponse;
import ru.alfabank.analyzer.model.JenkinsJobBuildsRequest;
import ru.alfabank.analyzer.model.JenkinsJobBuildsResponse;
import ru.alfabank.analyzer.model.JenkinsLogsRequest;
import ru.alfabank.analyzer.model.JenkinsLogsResponse;
import ru.alfabank.analyzer.model.LogCacheClearRequest;
import ru.alfabank.analyzer.model.LogCacheClearResponse;
import ru.alfabank.analyzer.model.LogCacheStatsRequest;
import ru.alfabank.analyzer.model.LogCacheStatsResponse;
import ru.alfabank.analyzer.model.McpEnvelope;
import ru.alfabank.analyzer.model.TailLogsRequest;
import ru.alfabank.analyzer.model.TailLogsResponse;
import ru.alfabank.analyzer.service.ErrorScannerService;
import ru.alfabank.analyzer.service.ErrorScannerService.ErrorScanResult;
import ru.alfabank.analyzer.service.JenkinsLogService;
import ru.alfabank.analyzer.service.LogSearchService;
import ru.alfabank.analyzer.service.jenkins.JenkinsApiService;
import ru.alfabank.analyzer.service.LogSearchService.GrepScanResult;
import ru.alfabank.analyzer.util.LogLineTruncationService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JenkinsAnalyzerTools {

    private static final List<String> CREDENTIALS_REMEDIATION = List.of(
            "Set JENKINS_USER (or JENKINS_USERNAME) and JENKINS_TOKEN (or JENKINS_API_TOKEN) in the MCP server process environment.",
            "Restart the MCP server after changing env vars (stdio servers load env at process start).",
            "Ensure the token is a Jenkins API token with access to the job URL you passed."
    );

    private final JenkinsLogService jenkinsLogService;
    private final JenkinsApiService jenkinsApiService;
    private final ErrorScannerService errorScannerService;
    private final LogSearchService logSearchService;
    private final LogLineTruncationService lineTruncation;
    private final Validator validator;

    public JenkinsAnalyzerTools(
            JenkinsLogService jenkinsLogService,
            JenkinsApiService jenkinsApiService,
            ErrorScannerService errorScannerService,
            LogSearchService logSearchService,
            LogLineTruncationService lineTruncation,
            Validator validator
    ) {
        this.jenkinsLogService = jenkinsLogService;
        this.jenkinsApiService = jenkinsApiService;
        this.errorScannerService = errorScannerService;
        this.logSearchService = logSearchService;
        this.lineTruncation = lineTruncation;
        this.validator = validator;
    }

    @Tool(
            name = "get_full_logs",
            description = """
                    Download Jenkins console output for one build/job as plain text lines (console log).

                    Triage matrix — step 4 (heavy): use after tail / error-scan / grep if you still need a broad full log or export. Prefer smaller tools first (see MCP server instructions).

                    When to use: you need many lines returned at once for broad scanning or exporting to the user.
                    Prefer `get_error_lines_with_context` first if you only need failures; prefer `grep_in_logs` if you know the search string.

                    Input:
                    - jenkinsUrl: Jenkins UI URL for the build/job OR a direct `.../consoleText` URL. The tool normalizes to `consoleText`.
                    - maxLogLines (optional): cap how many lines are returned (default 12000, min 500, max 50000). The file cache still stores the full downloaded body when the build is final.
                    - scanWholeLog (optional): default false. If true, ignores maxLogLines and scans the entire log file.

                    Auth (IMPORTANT): HTTP Basic auth is NOT passed as tool arguments. Configure the MCP server environment:
                    - JENKINS_USER or JENKINS_USERNAME
                    - JENKINS_TOKEN or JENKINS_API_TOKEN

                    Caching: if the log contains a final `Finished:` marker typical of completed builds, the full console text is written under `analyzer.log-cache.dir` and subsequent tool calls reuse the file.

                    Output envelope: always `success`+`data` on OK; on failure `success=false` with `agentMessage`, `errorCode`, `technicalDetail`, and `remediation`.
                    """
    )
    public McpEnvelope<JenkinsLogsResponse> getFullLogs(JenkinsLogsRequest request) {
        Optional<McpEnvelope<JenkinsLogsResponse>> invalid = validationEnvelope(request);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        try {
            List<String> logs = jenkinsLogService.downloadLogs(request.jenkinsUrl(), request.effectiveMaxLogLines());
            List<String> displayLines = logs.stream().map(lineTruncation::forDisplay).toList();
            return McpEnvelope.ok(new JenkinsLogsResponse(
                    request.jenkinsUrl(),
                    logs.size(),
                    displayLines
            ));
        } catch (IllegalArgumentException e) {
            return McpEnvelope.error(
                    "invalid_argument",
                    e.getMessage(),
                    stackSummary(e),
                    List.of("Verify jenkinsUrl is a complete URL to a Jenkins job or build.")
            );
        } catch (IllegalStateException e) {
            return mapJenkinsStateError(e);
        } catch (IOException e) {
            return McpEnvelope.error(
                    "io_error",
                    "Could not read or fetch Jenkins log data.",
                    e.getMessage(),
                    List.of("See server stderr, or the file set via LOG_FILE if configured, for stack trace.", "Check disk space and cache directory permissions.")
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpEnvelope.error(
                    "interrupted",
                    "Jenkins log download was interrupted.",
                    e.getMessage(),
                    List.of("Retry the tool call.")
            );
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    @Tool(
            name = "get_logs_tail",
            description = """
                    Return the last N lines of a Jenkins console log.

                    Triage matrix — step 1 (fast): use first when the failure is likely near the end of the log (see MCP server instructions).

                    Use this when failures are likely near the end of the build output (common CI pattern).
                    This tool is optimized for quick triage and smaller payloads.

                    Input:
                    - jenkinsUrl: Jenkins job/build URL or direct .../consoleText URL.
                    - tailLines (optional): number of lines from the end (default 500, min 1, max 10000).

                    Behavior:
                    - Reuses cached final logs when available.
                    - Returns both totalLogLines and returnedLines so the agent can reason about truncation.
                    """
    )
    public McpEnvelope<TailLogsResponse> getLogsTail(TailLogsRequest request) {
        Optional<McpEnvelope<TailLogsResponse>> invalid = validationEnvelope(request);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        try {
            JenkinsLogService.TailLogs tail = jenkinsLogService.tailLogs(
                    request.jenkinsUrl(),
                    request.effectiveTailLines()
            );
            List<String> displayTail = tail.tailLines().stream().map(lineTruncation::forDisplay).toList();
            return McpEnvelope.ok(new TailLogsResponse(
                    request.jenkinsUrl(),
                    tail.totalLogLines(),
                    displayTail.size(),
                    displayTail
            ));
        } catch (IllegalArgumentException e) {
            return McpEnvelope.error(
                    "invalid_argument",
                    e.getMessage(),
                    stackSummary(e),
                    List.of("Verify jenkinsUrl.")
            );
        } catch (IllegalStateException e) {
            return mapJenkinsStateError(e);
        } catch (IOException e) {
            return McpEnvelope.error(
                    "io_error",
                    "Could not read or fetch Jenkins log data.",
                    e.getMessage(),
                    List.of("See server log file for details.")
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpEnvelope.error(
                    "interrupted",
                    "Jenkins log download was interrupted.",
                    e.getMessage(),
                    List.of("Retry the tool call.")
            );
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    @Tool(
            name = "get_log_cache_stats",
            description = """
                    Return statistics for the on-disk Jenkins console log cache (directory path, file count, total bytes).

                    Use for troubleshooting or before calling `clear_log_cache`. Does not call Jenkins.
                    """
    )
    public McpEnvelope<LogCacheStatsResponse> getLogCacheStats(LogCacheStatsRequest request) {
        Optional<McpEnvelope<LogCacheStatsResponse>> invalid = validationEnvelope(request);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        try {
            JenkinsLogService.LogCacheStatsSnapshot s = jenkinsLogService.getLogCacheStats();
            return McpEnvelope.ok(new LogCacheStatsResponse(
                    s.cacheDirectory(),
                    s.fileCount(),
                    s.totalBytes()
            ));
        } catch (IOException e) {
            return McpEnvelope.error(
                    "io_error",
                    "Could not read log cache directory.",
                    e.getMessage(),
                    List.of("Check analyzer.log-cache.dir permissions.")
            );
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    @Tool(
            name = "clear_log_cache",
            description = """
                    Delete all cached Jenkins console log files (`*.log`) from the cache directory.

                    Next log fetch will re-download from Jenkins. Does not affect running builds.
                    """
    )
    public McpEnvelope<LogCacheClearResponse> clearLogCache(LogCacheClearRequest request) {
        Optional<McpEnvelope<LogCacheClearResponse>> invalid = validationEnvelope(request);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        try {
            int deleted = jenkinsLogService.clearLogCache();
            return McpEnvelope.ok(new LogCacheClearResponse(deleted));
        } catch (IOException e) {
            return McpEnvelope.error(
                    "io_error",
                    "Could not clear log cache.",
                    e.getMessage(),
                    List.of("Check analyzer.log-cache.dir permissions.")
            );
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    @Tool(
            name = "describe_jenkins_job_or_build",
            description = """
                    Resolve a Jenkins job or build URL via the JSON REST API (no HTML parsing).

                    Returns job name, optional build number, result status (e.g. SUCCESS, FAILURE), building flag, timestamps,
                    and branch when detectable (Git actions in API, else multibranch path heuristic .../job/repo/job/<branch>/...).

                    If the URL points at a job (no build number), includes metadata for the last completed build when available.

                    Auth: same env-based Basic credentials as log tools.
                    """
    )
    public McpEnvelope<JenkinsDescribeResponse> describeJenkinsJobOrBuild(JenkinsDescribeRequest request) {
        Optional<McpEnvelope<JenkinsDescribeResponse>> invalid = validationEnvelope(request);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        try {
            JenkinsApiService.JenkinsDescribeResult r = jenkinsApiService.describe(request.jenkinsUrl());
            var p = r.parsed();
            return McpEnvelope.ok(new JenkinsDescribeResponse(
                    p.jenkinsRoot(),
                    p.jobSegments(),
                    r.jobName(),
                    r.jobFullName(),
                    r.buildNumber(),
                    r.result(),
                    r.building(),
                    r.timestamp(),
                    r.duration(),
                    r.branch(),
                    r.branchSource(),
                    r.resolvedUiUrl(),
                    r.buildUrl()
            ));
        } catch (IllegalArgumentException e) {
            return McpEnvelope.error(
                    "invalid_argument",
                    e.getMessage(),
                    stackSummary(e),
                    List.of("Pass a Jenkins URL containing /job/..., optionally ending with a build number.", "Strip /console or /consoleText if present.")
            );
        } catch (IllegalStateException e) {
            return mapJenkinsStateError(e);
        } catch (IOException e) {
            return McpEnvelope.error(
                    "io_error",
                    "Jenkins API request failed.",
                    e.getMessage(),
                    List.of("Check network, VPN, and Jenkins URL.")
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpEnvelope.error(
                    "interrupted",
                    "Jenkins API request was interrupted.",
                    e.getMessage(),
                    List.of("Retry the tool call.")
            );
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    @Tool(
            name = "list_jenkins_job_builds",
            description = """
                    List recent builds for a Jenkins job using the JSON REST API (no HTML).

                    Accepts a job URL or a build URL (the job is inferred). Newest-first list includes build number, result,
                    building flag, timestamps, and Jenkins UI URL per build.

                    limit (optional): default 20, max 100.

                    Auth: same env-based Basic credentials as log tools.
                    """
    )
    public McpEnvelope<JenkinsJobBuildsResponse> listJenkinsJobBuilds(JenkinsJobBuildsRequest request) {
        Optional<McpEnvelope<JenkinsJobBuildsResponse>> invalid = validationEnvelope(request);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        try {
            JenkinsApiService.JenkinsJobBuildsResult r = jenkinsApiService.listBuilds(
                    request.jenkinsUrl(),
                    request.effectiveLimit()
            );
            var p = r.parsed();
            List<JenkinsJobBuildsResponse.BuildSummary> rows = r.builds().stream()
                    .map(b -> new JenkinsJobBuildsResponse.BuildSummary(
                            b.number(),
                            b.result(),
                            b.building(),
                            b.timestamp(),
                            b.duration(),
                            b.url()
                    ))
                    .toList();
            return McpEnvelope.ok(new JenkinsJobBuildsResponse(
                    p.jenkinsRoot(),
                    p.jobSegments(),
                    r.jobName(),
                    r.jobFullName(),
                    rows
            ));
        } catch (IllegalArgumentException e) {
            return McpEnvelope.error(
                    "invalid_argument",
                    e.getMessage(),
                    stackSummary(e),
                    List.of("jenkinsUrl must contain /job/....", "limit must be 1..100.")
            );
        } catch (IllegalStateException e) {
            return mapJenkinsStateError(e);
        } catch (IOException e) {
            return McpEnvelope.error(
                    "io_error",
                    "Jenkins API request failed.",
                    e.getMessage(),
                    List.of("Check network, VPN, and Jenkins URL.")
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpEnvelope.error(
                    "interrupted",
                    "Jenkins API request was interrupted.",
                    e.getMessage(),
                    List.of("Retry the tool call.")
            );
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    @Tool(
            name = "get_error_lines_with_context",
            description = """
                    Extract console lines that look like errors (e.g. Maven/Gradle `[ERROR]`, or ` error ` tokens) plus nearby context.

                    Triage matrix — step 2: heuristic errors over the full log; compare totalErrorMatches vs returnedErrorMatches when maxErrorMatches caps the list (see MCP server instructions).

                    When to use: quickly isolate failure noise from build tools. The full Jenkins console log is always loaded and scanned.

                    Input:
                    - jenkinsUrl: same as `get_full_logs`.
                    - maxErrorMatches (optional): cap how many error matches are returned (1..5000). If omitted, returns every match in order.
                    - contextLines (optional): lines before/after each match (default 2, max 20). Increase carefully; responses grow quickly.

                    Breaking change vs older versions: `maxLogLines` / `scanWholeLog` were removed — this tool always scans the entire log.

                    Auth & cache: identical to `get_full_logs` (env-based credentials; file-cache for final logs).

                    Output: `data.totalErrorMatches` is the count in the full log; `data.returnedErrorMatches` may be lower if `maxErrorMatches` was set.
                    `data.matches` lists each returned hit with `lineNumber` (1-based) and `context`; `isErrorLine` marks the hit line.
                    """
    )
    public McpEnvelope<ErrorLinesResponse> getErrorLinesWithContext(ErrorLinesRequest request) {
        Optional<McpEnvelope<ErrorLinesResponse>> invalid = validationEnvelope(request);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        try {
            List<String> logs = jenkinsLogService.downloadLogs(request.jenkinsUrl(), Integer.MAX_VALUE);
            ErrorScanResult scan = errorScannerService.findErrorLinesWithContext(
                    logs,
                    request.effectiveContextLines(),
                    request.effectiveMaxErrorMatches()
            );
            return McpEnvelope.ok(new ErrorLinesResponse(
                    request.jenkinsUrl(),
                    logs.size(),
                    scan.totalErrorMatches(),
                    scan.returnedErrorMatches(),
                    scan.matches()
            ));
        } catch (IllegalArgumentException e) {
            return McpEnvelope.error(
                    "invalid_argument",
                    e.getMessage(),
                    stackSummary(e),
                    List.of("Verify jenkinsUrl.")
            );
        } catch (IllegalStateException e) {
            return mapJenkinsStateError(e);
        } catch (IOException e) {
            return McpEnvelope.error(
                    "io_error",
                    "Could not read or fetch Jenkins log data.",
                    e.getMessage(),
                    List.of("See server log file for details.", "Check Jenkins connectivity and cache directory.")
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpEnvelope.error(
                    "interrupted",
                    "Jenkins log download was interrupted.",
                    e.getMessage(),
                    List.of("Retry the tool call.")
            );
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    @Tool(
            name = "grep_in_logs",
            description = """
                    Search the entire Jenkins console log and return matches with context.

                    Triage matrix — step 3: targeted search when you have a substring or regex; compare totalGrepMatches vs returnedGrepMatches when maxMatches caps the list (see MCP server instructions).

                    The full log is always loaded and scanned (same as `get_error_lines_with_context`). Limit output with `maxMatches` only.

                    When to use: you know part of an exception message, task name, file path fragment, plugin error code, or a failing test name.

                    Input:
                    - jenkinsUrl: same as `get_full_logs`.
                    - query: plain substring unless `useRegex=true`, then a Java regex (invalid patterns return validation-like errors).
                    - contextLines (optional): default 2 (0..20).
                    - maxMatches (optional): default 100 (1..5000) — max matches returned; response includes `totalGrepMatches` vs `returnedGrepMatches`.
                    - ignoreCase (optional): default true for substring; for regex, maps to `Pattern.CASE_INSENSITIVE` when true.
                    - useRegex (optional): default false. If true, `query` is compiled as regex and `find()` is used per line.

                    Auth & cache: same as other tools.

                    Tip: run `get_error_lines_with_context` first if you do not yet know what to search for; then use a distinctive substring or regex as `query`.
                    """
    )
    public McpEnvelope<GrepLogsResponse> grepInLogs(GrepLogsRequest request) {
        Optional<McpEnvelope<GrepLogsResponse>> invalid = validationEnvelope(request);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        try {
            List<String> logs = jenkinsLogService.downloadLogs(request.jenkinsUrl(), Integer.MAX_VALUE);
            GrepScanResult scan = logSearchService.grepWithContext(
                    logs,
                    request.query(),
                    request.effectiveContextLines(),
                    request.effectiveMaxMatches(),
                    request.effectiveIgnoreCase(),
                    request.effectiveUseRegex(),
                    lineTruncation
            );
            return McpEnvelope.ok(new GrepLogsResponse(
                    request.jenkinsUrl(),
                    request.query(),
                    request.effectiveUseRegex(),
                    logs.size(),
                    scan.totalGrepMatches(),
                    scan.returnedGrepMatches(),
                    scan.matches()
            ));
        } catch (IllegalArgumentException e) {
            return McpEnvelope.error(
                    "invalid_argument",
                    e.getMessage(),
                    stackSummary(e),
                    List.of("Verify jenkinsUrl and query.", "For useRegex=true, ensure query is valid Java regex syntax.")
            );
        } catch (IllegalStateException e) {
            return mapJenkinsStateError(e);
        } catch (IOException e) {
            return McpEnvelope.error(
                    "io_error",
                    "Could not read or fetch Jenkins log data.",
                    e.getMessage(),
                    List.of("See server log file for details.")
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpEnvelope.error(
                    "interrupted",
                    "Jenkins log download was interrupted.",
                    e.getMessage(),
                    List.of("Retry the tool call.")
            );
        } catch (Exception e) {
            return unexpected(e);
        }
    }

    private <T> Optional<McpEnvelope<T>> validationEnvelope(Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return Optional.empty();
        }
        String detail = violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return Optional.of(McpEnvelope.error(
                "validation_failed",
                "One or more tool arguments are invalid. Fix them and call the tool again.",
                detail,
                List.of(
                        "Read `technicalDetail` for the exact field violations.",
                        "jenkinsUrl must be a non-empty Jenkins job/build URL.",
                        "For grep_in_logs, query must be non-empty.",
                        "Bounds: grep maxMatches 1..5000; error maxErrorMatches 1..5000; contextLines 0..20.",
                        "grep_in_logs and get_error_lines_with_context always load the full log; use maxMatches / maxErrorMatches to cap returned hits.",
                        "analyzer.log-line-max-chars (env ANALYZER_LOG_LINE_MAX_CHARS) controls per-line truncation in responses.",
                        "describe_jenkins_job_or_build / list_jenkins_job_builds need URLs with /job/...; list_jenkins_job_builds limit is 1..100."
                )
        ));
    }

    private <T> McpEnvelope<T> mapJenkinsStateError(IllegalStateException e) {
        String msg = e.getMessage() == null ? "Unknown Jenkins error" : e.getMessage();
        List<String> remediation = new ArrayList<>();
        remediation.add("Verify jenkinsUrl points to an existing Jenkins job or build (and /consoleText for log tools).");
        if (msg.contains("HTTP 401") || msg.contains("HTTP 403") || msg.contains("No credentials were sent")) {
            remediation.addAll(CREDENTIALS_REMEDIATION);
        }
        return McpEnvelope.error(
                "jenkins_http_error",
                "Jenkins refused the log request or the URL was wrong. Read technicalDetail for HTTP specifics.",
                msg,
                remediation
        );
    }

    private static <T> McpEnvelope<T> unexpected(Exception e) {
        return McpEnvelope.error(
                "unexpected_error",
                "An unexpected error occurred in the MCP server while handling this tool call.",
                stackSummary(e),
                List.of(
                        "Inspect stderr, or the file set via LOG_FILE if configured, on the machine running the MCP server.",
                        "Retry once; if it persists, capture errorCode=unexpected_error and the log snippet."
                )
        );
    }

    private static String stackSummary(Throwable e) {
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return e.getClass().getSimpleName();
    }
}
