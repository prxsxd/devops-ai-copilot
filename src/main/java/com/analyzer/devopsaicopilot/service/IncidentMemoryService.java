package com.analyzer.devopsaicopilot.service;

import com.analyzer.devopsaicopilot.model.Incident;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Incident memory (RAG) backed by Azure AI Search.
 *
 * <p>Embeddings are produced by {@link EmbeddingService} (text-embedding-3-small)
 * and the vectors are stored and ranked by {@link AzureSearchService}. This
 * class only orchestrates embedding + delegation, so the agent loop and
 * controller are unaware of the storage technology.
 *
 * <p>Incidents are loaded externally (e.g. via the PowerShell seed script
 * hitting {@code POST /api/incidents}); every real analysis also persists itself
 * here. Because the index is persistent, data survives restarts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentMemoryService {

    private final EmbeddingService embeddingService;
    private final AzureSearchService searchService;

    /** Embeds the incident and upserts it into the search index. */
    public void store(Incident incident) {
        if (incident.getCreatedAt() == null) {
            incident.setCreatedAt(Instant.now());
        }
        log.info("[MEMORY] Embedding + storing incident type={} jira={}",
                incident.getFailureType(), incident.getJiraKey());
        double[] vector = embeddingService.embed(incidentText(incident));
        incident.setEmbedding(vector);
        searchService.upload(incident);
    }

    public List<Incident> all() {
        return searchService.listAll();
    }

    /**
     * Returns up to {@code topK} past incidents most similar to the query,
     * ranked by semantic (vector) similarity in Azure AI Search.
     */
    public List<Incident> search(String failureType, String query, int topK) {
        String safeType = failureType == null ? "" : failureType;
        String safeQuery = query == null ? "" : query;
        String queryText = (safeType + " " + safeQuery).trim();
        log.info("[MEMORY] Vector search type={} query='{}' topK={}",
                safeType, safeQuery, topK);

        double[] queryEmbedding = embeddingService.embed(queryText);
        if (queryEmbedding == null) {
            return List.of();
        }
        return searchService.vectorSearch(queryEmbedding, topK);
    }

    private String incidentText(Incident incident) {
        return String.join(" ",
                safe(incident.getFailureType()),
                safe(incident.getSummary()),
                safe(incident.getRootCause()),
                safe(incident.getSuggestedFix()));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
