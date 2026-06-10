package com.analyzer.devopsaicopilot.service;

import com.analyzer.devopsaicopilot.model.AiAnalysisResponse;
import com.analyzer.devopsaicopilot.model.FailureAnalysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AzureOpenAiService {

    private final OpenAIClient client;
    private final ObjectMapper objectMapper;

    @Value("${azure.openai.deployment}")
    private String deploymentName;

    public AiAnalysisResponse analyze(FailureAnalysis analysis) {

        String prompt = """
                Analyze the following CI/CD failure.

                Failure Type:
                %s

                Logs:
                %s

                Respond ONLY with a valid JSON object (no markdown, no code
                fences) using exactly these keys:

                {
                  "rootCause": "...",
                  "severity": "...",
                  "suggestedFix": "...",
                  "jiraSummary": "...",
                  "jiraDescription": "..."
                }

                Rules:
                - "severity" must be one of: Low, Medium, High, Critical.
                - "jiraSummary" must be a short one-line title.
                - "jiraDescription" must contain the root cause and the
                  suggested fix in readable plain text.
                """
                .formatted(
                        analysis.getFailureType(),
                        analysis.getExtractedLogs()
                );

        ChatCompletionCreateParams params =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.of(deploymentName))
                        .addSystemMessage("""
                                You are a senior DevOps engineer.
                                Analyze CI/CD failures and provide
                                actionable recommendations.
                                Always respond with valid JSON only.
                                """)
                        .addUserMessage(prompt)
                        .build();

        ChatCompletion response =
                client.chat()
                        .completions()
                        .create(params);

        String content = response.choices()
                .get(0)
                .message()
                .content()
                .orElse("");

        System.out.println("=================================");
        System.out.println("AI MODEL FINAL RESULT:");
        System.out.println(content);
        System.out.println("=================================");

        return parse(content);
    }

    private AiAnalysisResponse parse(String content) {
        try {
            String json = extractJson(content);
            return objectMapper.readValue(json, AiAnalysisResponse.class);
        } catch (Exception e) {
            AiAnalysisResponse fallback = new AiAnalysisResponse();
            fallback.setRootCause("Could not parse AI response.");
            fallback.setSeverity("Medium");
            fallback.setSuggestedFix("Review the raw AI output below.");
            fallback.setJiraSummary("CI/CD failure analysis");
            fallback.setJiraDescription(content);
            return fallback;
        }
    }

    /**
     * Strips markdown code fences if the model wrapped the JSON in them.
     */
    private String extractJson(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstBrace = trimmed.indexOf('{');
            int lastBrace = trimmed.lastIndexOf('}');
            if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
                return trimmed.substring(firstBrace, lastBrace + 1);
            }
        }
        return trimmed;
    }
}
