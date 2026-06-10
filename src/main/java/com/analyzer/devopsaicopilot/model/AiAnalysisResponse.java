package com.analyzer.devopsaicopilot.model;

import lombok.Data;

@Data
public class AiAnalysisResponse {

    private String rootCause;

    private String severity;

    private String suggestedFix;

    private String jiraSummary;

    private String jiraDescription;
}
