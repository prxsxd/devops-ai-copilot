package com.analyzer.devopsaicopilot.service;

import com.analyzer.devopsaicopilot.model.AgentResult;
import com.analyzer.devopsaicopilot.model.AiAnalysisResponse;
import com.analyzer.devopsaicopilot.model.FailureAnalysis;
import com.analyzer.devopsaicopilot.model.Incident;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AzureOpenAiService {

    /** Hard cap on agent turns to bound credit usage. */
    private static final int MAX_TURNS = 4;

    private static final String SEARCH_TOOL = "searchPastIncidents";

    private final OpenAIClient client;
    private final ObjectMapper objectMapper;
    private final IncidentMemoryService incidentMemoryService;

    @Value("${azure.openai.deployment}")
    private String deploymentName;

    /**
     * The single tool the agent is allowed to call. The model decides when to
     * invoke it; our code runs the actual retrieval against the incident memory.
     */
    private ChatCompletionTool searchPastIncidentsTool() {
        FunctionParameters parameters = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                        "failureType", Map.of(
                                "type", "string",
                                "description", "The failure type, e.g. OOM_KILLED, "
                                        + "MAVEN_COMPILATION, KUBERNETES_CRASH, "
                                        + "IMAGE_PULL, UNIT_TEST."),
                        "query", Map.of(
                                "type", "string",
                                "description", "A short snippet of the key error "
                                        + "message or symptoms to match against "
                                        + "past incidents."))))
                .putAdditionalProperty("required",
                        JsonValue.from(List.of("failureType", "query")))
                .build();

        return ChatCompletionTool.builder()
                .function(FunctionDefinition.builder()
                        .name(SEARCH_TOOL)
                        .description("Search the incident memory for past CI/CD "
                                + "failures similar to the current one. Returns prior "
                                + "root causes, the fixes that resolved them, and the "
                                + "Jira ticket keys. Call this BEFORE giving your final "
                                + "analysis so you can reuse proven fixes and reference "
                                + "previous tickets.")
                        .parameters(parameters)
                        .build())
                .build();
    }

    public AgentResult analyze(FailureAnalysis analysis) {

        List<String> steps = new ArrayList<>();
        List<Incident> matchedIncidents = new ArrayList<>();
        steps.add("Classified failure as " + analysis.getFailureType());

        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.of(deploymentName))
                        .addTool(searchPastIncidentsTool())
                        .addSystemMessage("""
                                You are a senior DevOps engineer with access to an
                                incident-memory tool. First call searchPastIncidents
                                to check whether a similar failure has been seen and
                                fixed before. Use any matches to inform your answer
                                and reference the prior Jira key when relevant.
                                When you have enough information, respond with valid
                                JSON only (no tool call, no markdown).
                                """)
                        .addUserMessage(buildPrompt(analysis));

        String finalContent = "";

        log.info("[AGENT] Starting tool-calling loop (max {} turns)", MAX_TURNS);
        for (int turn = 0; turn < MAX_TURNS; turn++) {

            log.info("[AGENT] Turn {} - calling model {}", turn + 1, deploymentName);
            steps.add("Turn " + (turn + 1) + ": asked " + deploymentName + " to reason");
            ChatCompletion response =
                    client.chat().completions().create(builder.build());

            ChatCompletionMessage message =
                    response.choices().get(0).message();

            // Keep the assistant turn in the conversation history.
            builder.addMessage(message);

            List<ChatCompletionMessageToolCall> toolCalls =
                    message.toolCalls().orElse(List.of());

            if (toolCalls.isEmpty()) {
                log.info("[AGENT] Turn {} - no tool call, final answer ready", turn + 1);
                steps.add("Agent produced its final analysis");
                finalContent = message.content().orElse("");
                break;
            }

            log.info("[AGENT] Turn {} - model requested {} tool call(s)",
                    turn + 1, toolCalls.size());
            for (ChatCompletionMessageToolCall toolCall : toolCalls) {
                String result = runTool(toolCall.function(), steps, matchedIncidents);
                builder.addMessage(
                        ChatCompletionToolMessageParam.builder()
                                .toolCallId(toolCall.id())
                                .content(result)
                                .build());
            }
        }

        log.info("[AGENT] Final result:\n{}", finalContent);

        return new AgentResult(parse(finalContent), steps, matchedIncidents);
    }

    private String buildPrompt(FailureAnalysis analysis) {
        return """
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
                  suggested fix in readable plain text. If a similar past
                  incident was found, mention its Jira key.
                """
                .formatted(
                        analysis.getFailureType(),
                        analysis.getExtractedLogs());
    }

    /**
     * Executes a tool call requested by the model. Only searchPastIncidents is
     * registered, so we parse its JSON arguments and query the incident memory.
     */
    private String runTool(ChatCompletionMessageToolCall.Function function,
                           List<String> steps, List<Incident> matchedIncidents) {
        try {
            Map<String, String> args = parseArgs(function.arguments());
            String failureType = args.getOrDefault("failureType", "");
            String query = args.getOrDefault("query", "");

            List<Incident> matches =
                    incidentMemoryService.search(failureType, query, 3);

            log.info("[AGENT] TOOL CALL {}(type={}, query='{}') -> {} match(es)",
                    SEARCH_TOOL, failureType, query, matches.size());
            steps.add("Searched incident memory for '" + query + "' -> "
                    + matches.size() + " match(es)");
            matchedIncidents.addAll(matches);

            if (matches.isEmpty()) {
                return "No similar past incidents found in memory.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(matches.size())
                    .append(" similar past incident(s):\n");
            for (Incident incident : matches) {
                sb.append("- [").append(incident.getJiraKey()).append("] ")
                        .append(incident.getFailureType()).append(": ")
                        .append(incident.getSummary())
                        .append(" | Root cause: ").append(incident.getRootCause())
                        .append(" | Fix that worked: ").append(incident.getSuggestedFix())
                        .append("\n");
            }
            return sb.toString();

        } catch (Exception e) {
            return "Incident memory lookup failed: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseArgs(String argumentsJson) throws Exception {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(argumentsJson, Map.class);
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
