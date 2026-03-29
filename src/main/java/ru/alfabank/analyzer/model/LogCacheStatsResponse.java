package ru.alfabank.analyzer.model;

public record LogCacheStatsResponse(
        String cacheDirectory,
        int fileCount,
        long totalBytes
) {
}
