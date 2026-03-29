package ru.alfabank.analyzer.service;

import org.springframework.stereotype.Service;
import ru.alfabank.analyzer.model.ErrorLinesResponse;
import ru.alfabank.analyzer.util.LogLineTruncationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ErrorScannerService {
    private final LogLineTruncationService truncation;

    public ErrorScannerService(LogLineTruncationService truncation) {
        this.truncation = truncation;
    }

    public List<String> findErrorLines(List<String> logLines) {
        return logLines.stream()
                .filter(this::isErrorLine)
                .toList();
    }

    /**
     * Scans the full {@code logLines}. If {@code maxErrorMatches} is non-null, only the first
     * {@code maxErrorMatches} matches (in file order) are returned; {@link ErrorScanResult#totalErrorMatches()}
     * is always the full count.
     */
    public ErrorScanResult findErrorLinesWithContext(
            List<String> logLines,
            int contextLines,
            Integer maxErrorMatches
    ) {
        List<ErrorLinesResponse.ErrorMatch> allMatches = new ArrayList<>();
        for (int i = 0; i < logLines.size(); i++) {
            if (!isErrorLine(logLines.get(i))) {
                continue;
            }
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
            allMatches.add(new ErrorLinesResponse.ErrorMatch(i + 1, context));
        }

        int total = allMatches.size();
        if (maxErrorMatches == null || allMatches.size() <= maxErrorMatches) {
            return new ErrorScanResult(List.copyOf(allMatches), total, total);
        }
        return new ErrorScanResult(
                new ArrayList<>(allMatches.subList(0, maxErrorMatches)),
                total,
                maxErrorMatches
        );
    }

    private boolean isErrorLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("[error]")) {
            return true;
        }
        if (lower.contains(" error ")) {
            return true;
        }
        if (lower.contains("build failed")) {
            return true;
        }
        if (lower.contains("build failure")) {
            return true;
        }
        if (lower.contains("error:")) {
            return true;
        }
        if (lower.contains("fatal:")) {
            return true;
        }
        if (lower.contains("fatal error")) {
            return true;
        }
        if (lower.contains("compilation error")) {
            return true;
        }
        if (lower.contains("compilation failure")) {
            return true;
        }
        if (lower.contains("error occurred")) {
            return true;
        }
        if (lower.contains("task failed")) {
            return true;
        }
        if (lower.contains("> task ") && lower.contains("failed")) {
            return true;
        }
        return false;
    }

    public record ErrorScanResult(
            List<ErrorLinesResponse.ErrorMatch> matches,
            int totalErrorMatches,
            int returnedErrorMatches
    ) {
    }
}
