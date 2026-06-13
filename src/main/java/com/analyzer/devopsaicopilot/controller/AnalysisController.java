package com.analyzer.devopsaicopilot.controller;

import com.analyzer.devopsaicopilot.model.AgentResult;
import com.analyzer.devopsaicopilot.model.AiAnalysisResponse;
import com.analyzer.devopsaicopilot.model.AnalysisResult;
import com.analyzer.devopsaicopilot.model.FailureAnalysis;
import com.analyzer.devopsaicopilot.model.Incident;
import com.analyzer.devopsaicopilot.service.AzureOpenAiService;
import com.analyzer.devopsaicopilot.service.IncidentMemoryService;
import com.analyzer.devopsaicopilot.service.JiraService;
import com.analyzer.devopsaicopilot.service.LogExtractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AnalysisController {

    private final LogExtractionService extractionService;
    private final AzureOpenAiService azureOpenAiService;
    private final JiraService jiraService;
    private final IncidentMemoryService incidentMemoryService;

    @PostMapping(
            value = "/analyze",
            consumes = MediaType.TEXT_PLAIN_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AnalysisResult analyze(
            @RequestBody String logs) {

        log.info("[ANALYZE] Received logs ({} chars)", logs == null ? 0 : logs.length());

        FailureAnalysis analysis =
                extractionService.analyze(logs);
        log.info("[ANALYZE] Classified failure type: {}", analysis.getFailureType());

        AgentResult agentResult =
                azureOpenAiService.analyze(analysis);
        AiAnalysisResponse aiResponse = agentResult.getAnalysis();
        log.info("[ANALYZE] Agent finished - severity={}", aiResponse.getSeverity());

        String jiraUrl = jiraService.createIssue(aiResponse);
        log.info("[ANALYZE] Jira ticket created: {}", jiraUrl);
        String jiraKey = extractJiraKey(jiraUrl);

        // Remember this incident so future analyses can learn from it (RAG).
        incidentMemoryService.store(new Incident(
                analysis.getFailureType().name(),
                aiResponse.getJiraSummary(),
                aiResponse.getRootCause(),
                aiResponse.getSuggestedFix(),
                aiResponse.getSeverity(),
                jiraKey,
                Instant.now()));
        log.info("[ANALYZE] Incident persisted to memory - done.");

        return new AnalysisResult(
                analysis.getFailureType().name(),
                aiResponse.getRootCause(),
                aiResponse.getSeverity(),
                aiResponse.getSuggestedFix(),
                aiResponse.getJiraSummary(),
                jiraKey,
                jiraUrl,
                agentResult.getSteps(),
                agentResult.getMatchedIncidents());
    }

    /**
     * Exposes the incident memory so the agent's institutional knowledge can be
     * inspected (useful for demos and dashboards). No model calls, so it
     * consumes no credits.
     */
    @GetMapping("/incidents")
    public List<Incident> incidents() {
        return incidentMemoryService.all();
    }

    /**
     * Loads a past incident into the memory store (e.g. from a PowerShell seed
     * script). The incident is embedded on store so it becomes searchable.
     */
    @PostMapping(
            value = "/incidents",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Incident addIncident(@RequestBody Incident incident) {
        incidentMemoryService.store(incident);
        return incident;
    }

    private String extractJiraKey(String jiraUrl) {
        if (jiraUrl == null) {
            return "UNKNOWN";
        }
        int idx = jiraUrl.lastIndexOf('/');
        return idx >= 0 && idx < jiraUrl.length() - 1
                ? jiraUrl.substring(idx + 1)
                : jiraUrl;
    }
}
