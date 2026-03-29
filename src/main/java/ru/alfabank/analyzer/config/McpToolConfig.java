package ru.alfabank.analyzer.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.alfabank.analyzer.api.JenkinsAnalyzerTools;

@Configuration
public class McpToolConfig {
    @Bean
    ToolCallbackProvider toolCallbackProvider(JenkinsAnalyzerTools jenkinsAnalyzerTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(jenkinsAnalyzerTools)
                .build();
    }
}
