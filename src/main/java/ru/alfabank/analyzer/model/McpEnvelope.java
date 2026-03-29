package ru.alfabank.analyzer.model;

import java.util.List;

/**
 * Wraps tool outcomes so the agent always gets structured success vs failure with guidance.
 */
public record McpEnvelope<T>(
        boolean success,
        T data,
        String errorCode,
        String agentMessage,
        String technicalDetail,
        List<String> remediation
) {
    public static <T> McpEnvelope<T> ok(T data) {
        return new McpEnvelope<>(true, data, null, null, null, List.of());
    }

    public static <T> McpEnvelope<T> error(
            String code,
            String agentMessage,
            String technicalDetail,
            List<String> remediation
    ) {
        return new McpEnvelope<>(
                false,
                null,
                code,
                agentMessage,
                technicalDetail,
                remediation == null ? List.of() : remediation
        );
    }
}
