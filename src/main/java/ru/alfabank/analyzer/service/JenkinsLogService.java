package ru.alfabank.analyzer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class JenkinsLogService {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Path cacheDir;
    private final Duration cacheTtl;
    private final long cacheMaxBytes;

    public JenkinsLogService(
            @Value("${analyzer.log-cache.dir:.cache/jenkins-logs}") String cacheDir,
            @Value("${analyzer.log-cache.ttl:7d}") Duration cacheTtl,
            @Value("${analyzer.log-cache.max-size-bytes:262144000}") long cacheMaxBytes
    ) {
        this.cacheDir = Path.of(cacheDir);
        this.cacheTtl = cacheTtl;
        this.cacheMaxBytes = cacheMaxBytes;
    }

    /**
     * Loads Jenkins console text. Uses HTTP Basic auth from environment variables only:
     * {@code JENKINS_USER} or {@code JENKINS_USERNAME}, and {@code JENKINS_TOKEN} or {@code JENKINS_API_TOKEN}.
     */
    public List<String> downloadLogs(String jenkinsUrl, int maxLogLines) throws IOException, InterruptedException {
        return loadAllLogLines(jenkinsUrl).stream().limit(maxLogLines).toList();
    }

    public TailLogs tailLogs(String jenkinsUrl, int tailLines) throws IOException, InterruptedException {
        List<String> allLines = loadAllLogLines(jenkinsUrl);
        int from = Math.max(0, allLines.size() - tailLines);
        return new TailLogs(allLines.size(), allLines.subList(from, allLines.size()));
    }

    private static String jenkinsAuthHint(int status, String consoleTextUrl, boolean credentialsProvided) {
        String base = "Jenkins returned HTTP " + status + " for " + consoleTextUrl + ". ";
        if (!credentialsProvided) {
            return base
                    + "No credentials were sent. Set environment variables JENKINS_USER (or JENKINS_USERNAME) "
                    + "and JENKINS_TOKEN (or JENKINS_API_TOKEN), then restart the MCP server process so it picks them up.";
        }
        return base
                + "Credentials were sent via Basic auth from environment variables, but Jenkins rejected them. "
                + "Confirm the user can access this job and the token is a valid Jenkins API token (not a password, unless your Jenkins is configured for that).";
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

    private String toConsoleTextUrl(String jenkinsUrl) {
        if (jenkinsUrl == null || jenkinsUrl.isBlank()) {
            throw new IllegalArgumentException("jenkinsUrl must not be blank.");
        }
        String trimmed = jenkinsUrl.trim();
        if (trimmed.endsWith("/consoleText")) {
            return trimmed;
        }
        if (trimmed.endsWith("/")) {
            return trimmed + "consoleText";
        }
        return trimmed + "/consoleText";
    }

    private boolean isFinalLog(String body) {
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("finished: failure")
                || normalized.contains("finished: success")
                || normalized.contains("finished");
    }

    private Path cacheFile(String consoleTextUrl) {
        return cacheDir.resolve(sha256(consoleTextUrl) + ".log");
    }

    private List<String> loadAllLogLines(String jenkinsUrl) throws IOException, InterruptedException {
        String consoleTextUrl = toConsoleTextUrl(jenkinsUrl);
        cleanupCache();

        Path cacheFile = cacheFile(consoleTextUrl);
        if (Files.exists(cacheFile)) {
            try {
                return Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IOException(
                        "Failed to read cached Jenkins log from " + cacheFile.toAbsolutePath() + ": " + e.getMessage(),
                        e
                );
            }
        }

        Optional<String> user = firstNonBlankEnv("JENKINS_USER", "JENKINS_USERNAME");
        Optional<String> token = firstNonBlankEnv("JENKINS_TOKEN", "JENKINS_API_TOKEN");

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(consoleTextUrl))
                .timeout(Duration.ofSeconds(30))
                .GET();

        if (user.isPresent() && token.isPresent()) {
            String basicAuth = Base64.getEncoder().encodeToString(
                    (user.get() + ":" + token.get()).getBytes(StandardCharsets.UTF_8)
            );
            requestBuilder.header("Authorization", "Basic " + basicAuth);
        }

        final HttpResponse<String> response;
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException(
                    "Network error fetching Jenkins console log from " + consoleTextUrl + ". "
                            + "Check VPN, Jenkins host reachability, and URL correctness. Cause: " + e.getMessage(),
                    e
            );
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new IllegalStateException(jenkinsAuthHint(status, consoleTextUrl, user.isPresent() && token.isPresent()));
        }
        if (status == 404) {
            throw new IllegalStateException(
                    "Jenkins returned HTTP 404 for " + consoleTextUrl + ". "
                            + "Verify the build still exists and the URL points to a job/build (consoleText is appended automatically)."
            );
        }
        if (status < 200 || status >= 300) {
            String bodySample = shorten(response.body(), 500);
            throw new IllegalStateException(
                    "Failed to fetch Jenkins log, HTTP status=" + status + ", url=" + consoleTextUrl
                            + (bodySample.isEmpty() ? "" : ", responseSample=" + bodySample)
            );
        }

        String body = response.body();
        if (isFinalLog(body)) {
            Files.createDirectories(cacheDir);
            Files.writeString(cacheFile, body, StandardCharsets.UTF_8);
        }
        return body.lines().toList();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash cache key", e);
        }
    }

    private void cleanupCache() {
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        Instant threshold = Instant.now().minus(cacheTtl);
        try {
            try (Stream<Path> stream = Files.list(cacheDir)) {
                List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".log"))
                        .toList();

                for (Path file : files) {
                    Instant modified = Files.getLastModifiedTime(file).toInstant();
                    if (modified.isBefore(threshold)) {
                        Files.deleteIfExists(file);
                    }
                }
            }
            try (Stream<Path> stream = Files.list(cacheDir)) {
                List<Path> remaining = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".log"))
                        .toList();

                long totalBytes = remaining.stream().mapToLong(this::safeSize).sum();
                if (totalBytes <= cacheMaxBytes) {
                    return;
                }

                List<Path> byOldest = remaining.stream()
                        .sorted(Comparator.comparing(this::safeModifiedTime))
                        .toList();
                long current = totalBytes;
                for (Path file : byOldest) {
                    if (current <= cacheMaxBytes) {
                        break;
                    }
                    long size = safeSize(file);
                    Files.deleteIfExists(file);
                    current -= size;
                }
            }
        } catch (IOException ignored) {
            // Cache cleanup is best-effort; analysis should continue even if cleanup fails.
        }
    }

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private Instant safeModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    /**
     * Stats for on-disk cached console logs ({@code *.log} under {@link #cacheDir}).
     */
    public LogCacheStatsSnapshot getLogCacheStats() throws IOException {
        Path dir = cacheDir;
        if (!Files.isDirectory(dir)) {
            return new LogCacheStatsSnapshot(dir.toAbsolutePath().toString(), 0, 0L);
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".log"))
                    .toList();
            long bytes = files.stream().mapToLong(this::safeSize).sum();
            return new LogCacheStatsSnapshot(dir.toAbsolutePath().toString(), files.size(), bytes);
        }
    }

    /**
     * Deletes all {@code *.log} files in the cache directory. Returns how many files were removed.
     */
    public int clearLogCache() throws IOException {
        if (!Files.isDirectory(cacheDir)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> stream = Files.list(cacheDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".log"))
                    .toList();
            for (Path p : files) {
                Files.deleteIfExists(p);
                deleted++;
            }
        }
        return deleted;
    }

    private static String shorten(String s, int maxLen) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String t = s.strip().replace('\n', ' ');
        return t.length() <= maxLen ? t : t.substring(0, maxLen) + "...";
    }

    public record LogCacheStatsSnapshot(String cacheDirectory, int fileCount, long totalBytes) {
    }

    public record TailLogs(int totalLogLines, List<String> tailLines) {
    }
}
