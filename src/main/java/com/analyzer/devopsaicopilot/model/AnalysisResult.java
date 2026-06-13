package com.analyzer.devopsaicopilot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Full structured response for the UI: the classified failure, the AI analysis,
 * the Jira ticket that was created, the past incidents the agent reused, and the
 * step-by-step agent trace.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AnalysisResult {

    private String failureType;

    private String rootCause;

    private String severity;

    private String suggestedFix;

    private String jiraSummary;

    private String jiraKey;

    private String jiraUrl;

    private List<String> agentSteps;

    private List<Incident> matchedIncidents;
}
