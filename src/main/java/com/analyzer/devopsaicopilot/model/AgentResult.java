package com.analyzer.devopsaicopilot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of the agent loop: the parsed analysis plus a human-readable trace of
 * the steps the agent took and any past incidents it matched via RAG. The trace
 * is what the UI renders to show the agent "thinking".
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AgentResult {

    private AiAnalysisResponse analysis;

    private List<String> steps;

    private List<Incident> matchedIncidents;
}
