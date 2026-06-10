package com.analyzer.devopsaicopilot.service;

import com.analyzer.devopsaicopilot.model.AiAnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JiraService {

    private final RestClient jiraRestClient;

    @Value("${jira.base-url}")
    private String baseUrl;

    @Value("${jira.project-key}")
    private String projectKey;

    @Value("${jira.issue-type}")
    private String issueType;

    @Value("${jira.sprint-id}")
    private long sprintId;

    @Value("${jira.sprint-field}")
    private String sprintField;

    public String createIssue(AiAnalysisResponse analysis) {

        Map<String, Object> fields = new HashMap<>();
        fields.put("project", Map.of("key", projectKey));
        fields.put("summary", analysis.getJiraSummary());
        fields.put("issuetype", Map.of("name", issueType));
        fields.put("description", buildDescription(analysis));
        fields.put(sprintField, sprintId);

        Map<String, Object> body = Map.of("fields", fields);

        Map<String, Object> response = jiraRestClient.post()
                .uri("/rest/api/3/issue")
                .body(body)
                .retrieve()
                .body(Map.class);

        String issueKey = response != null
                ? String.valueOf(response.get("key"))
                : "UNKNOWN";

        String issueUrl = baseUrl + "/browse/" + issueKey;

        System.out.println("=================================");
        System.out.println("JIRA TICKET CREATED: " + issueKey);
        System.out.println("URL: " + issueUrl);
        System.out.println("=================================");

        return issueUrl;
    }

    /**
     * Builds an Atlassian Document Format (ADF) description, required by the
     * Jira Cloud REST API v3.
     */
    private Map<String, Object> buildDescription(AiAnalysisResponse analysis) {

        String text = """
                Severity: %s

                Root Cause:
                %s

                Suggested Fix:
                %s

                Details:
                %s
                """
                .formatted(
                        analysis.getSeverity(),
                        analysis.getRootCause(),
                        analysis.getSuggestedFix(),
                        analysis.getJiraDescription()
                );

        return Map.of(
                "type", "doc",
                "version", 1,
                "content", List.of(
                        Map.of(
                                "type", "paragraph",
                                "content", List.of(
                                        Map.of(
                                                "type", "text",
                                                "text", text
                                        )
                                )
                        )
                )
        );
    }
}
