package com.analyzer.devopsaicopilot.controller;

import com.analyzer.devopsaicopilot.model.AiAnalysisResponse;
import com.analyzer.devopsaicopilot.model.FailureAnalysis;
import com.analyzer.devopsaicopilot.service.AzureOpenAiService;
import com.analyzer.devopsaicopilot.service.JiraService;
import com.analyzer.devopsaicopilot.service.LogExtractionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AnalysisController {

    private final LogExtractionService extractionService;
    private final AzureOpenAiService azureOpenAiService;
    private final JiraService jiraService;

    @PostMapping(
            value = "/analyze",
            consumes = MediaType.TEXT_PLAIN_VALUE)
    public String analyze(
            @RequestBody String logs) {

        FailureAnalysis analysis =
                extractionService.analyze(logs);

        AiAnalysisResponse aiResponse =
                azureOpenAiService.analyze(analysis);

        String jiraUrl = jiraService.createIssue(aiResponse);

        return "Jira ticket created: " + jiraUrl;
    }
}
