package ru.alfabank.analyzer.service;

import org.springframework.stereotype.Service;
import ru.alfabank.analyzer.model.ErrorLinesResponse;
import ru.alfabank.analyzer.model.GrepLogsResponse;
import ru.alfabank.analyzer.util.LogLineTruncationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class LogSearchService {

    public GrepScanResult grepWithContext(
            List<String> logLines,
            String query,
            int contextLines,
            int maxMatches,
            boolean ignoreCase,
            boolean useRegex,
            LogLineTruncationService truncation
    ) {
        final Pattern pattern;
        if (useRegex) {
            try {
                int flags = ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
                pattern = Pattern.compile(query, flags);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid regex: " + e.getMessage());
            }
        } else {
            pattern = null;
        }

        List<Integer> hitIndices = new ArrayList<>();
        for (int i = 0; i < logLines.size(); i++) {
            String line = logLines.get(i);
            boolean hit;
            if (useRegex) {
                hit = pattern.matcher(line).find();
            } else {
                String candidate = ignoreCase ? line.toLowerCase(Locale.ROOT) : line;
                String needle = ignoreCase ? query.toLowerCase(Locale.ROOT) : query;
                hit = candidate.contains(needle);
            }
            if (hit) {
                hitIndices.add(i);
            }
        }

        int total = hitIndices.size();
        int limit = Math.min(maxMatches, total);
        List<GrepLogsResponse.LogMatch> matches = new ArrayList<>(limit);
        for (int k = 0; k < limit; k++) {
            int i = hitIndices.get(k);
            int from = Math.max(0, i - contextLines);
            int to = Math.min(logLines.size() - 1, i + contextLines);
            List<ErrorLinesResponse.ContextLine> context = new ArrayList<>();
            for (int j = from; j <= to; j++) {
                context.add(new ErrorLinesResponse.ContextLine(
                        j + 1,
                        truncation.forDisplay(logLines.get(j)),
                        j == i
                ));
            }
            matches.add(new GrepLogsResponse.LogMatch(i + 1, context));
        }
        return new GrepScanResult(matches, total, limit);
    }

    public record GrepScanResult(
            List<GrepLogsResponse.LogMatch> matches,
            int totalGrepMatches,
            int returnedGrepMatches
    ) {
    }
}
