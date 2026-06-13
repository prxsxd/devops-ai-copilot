package com.analyzer.devopsaicopilot.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A past CI/CD failure the agent has already analyzed. These records form the
 * "incident memory" the agent searches (via the searchPastIncidents tool) to
 * reuse proven fixes.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Incident {

    private String failureType;

    private String summary;

    private String rootCause;

    private String suggestedFix;

    private String severity;

    private String jiraKey;

    private Instant createdAt;

    /**
     * Cached semantic embedding of this incident (computed once when stored,
     * only when embeddings are enabled). Hidden from JSON responses so the
     * /incidents endpoint stays readable.
     */
    @JsonIgnore
    private double[] embedding;

    public Incident(String failureType, String summary, String rootCause,
                    String suggestedFix, String severity, String jiraKey,
                    Instant createdAt) {
        this.failureType = failureType;
        this.summary = summary;
        this.rootCause = rootCause;
        this.suggestedFix = suggestedFix;
        this.severity = severity;
        this.jiraKey = jiraKey;
        this.createdAt = createdAt;
    }
}
