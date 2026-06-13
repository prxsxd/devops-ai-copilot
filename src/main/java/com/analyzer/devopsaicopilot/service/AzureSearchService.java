package com.analyzer.devopsaicopilot.service;

import com.analyzer.devopsaicopilot.model.Incident;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent incident store backed by Azure AI Search (vector search).
 *
 * <p>This replaces the previous in-memory list: incidents and their 1536-dim
 * embeddings live in a managed search index, so they survive restarts and the
 * nearest-neighbour ranking is done by the service rather than in Java.
 *
 * <p>The index is created automatically on startup if it does not yet exist.
 * All calls use the Azure AI Search REST API directly to keep dependencies light.
 */
@Service
public class AzureSearchService {

    private static final String API_VERSION = "2023-11-01";
    private static final int EMBEDDING_DIMENSIONS = 1536;
    private static final String VECTOR_PROFILE = "vector-profile";
    private static final String SELECT_FIELDS =
            "failureType,summary,rootCause,suggestedFix,severity,jiraKey,createdAt";

    private final RestClient restClient;
    private final String indexName;
    private final double minScore;

    public AzureSearchService(
            @Value("${azure.search.endpoint}") String endpoint,
            @Value("${azure.search.key}") String apiKey,
            @Value("${azure.search.index:incidents}") String indexName,
            @Value("${azure.search.min-score:0.6}") double minScore) {

        String base = endpoint == null ? "" : endpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        this.restClient = RestClient.builder()
                .baseUrl(base)
                .defaultHeader("api-key", apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.indexName = indexName;
        this.minScore = minScore;
    }

    /** Creates (or updates) the incident index on startup. Idempotent. */
    @PostConstruct
    void ensureIndex() {
        try {
            restClient.put()
                    .uri("/indexes/{name}?api-version={v}", indexName, API_VERSION)
                    .body(buildIndexSchema())
                    .retrieve()
                    .toBodilessEntity();
            System.out.println("Azure AI Search index '" + indexName + "' is ready.");
        } catch (Exception e) {
            System.err.println("Failed to ensure search index '" + indexName
                    + "': " + e.getMessage());
        }
    }

    /** Upserts a single incident (with its embedding) into the index. */
    public void upload(Incident incident) {
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("@search.action", "mergeOrUpload");
            doc.put("id", documentId(incident));
            doc.put("failureType", incident.getFailureType());
            doc.put("summary", incident.getSummary());
            doc.put("rootCause", incident.getRootCause());
            doc.put("suggestedFix", incident.getSuggestedFix());
            doc.put("severity", incident.getSeverity());
            doc.put("jiraKey", incident.getJiraKey());
            doc.put("createdAt", incident.getCreatedAt() == null
                    ? Instant.now().toString()
                    : incident.getCreatedAt().toString());
            if (incident.getEmbedding() != null) {
                doc.put("embedding", toList(incident.getEmbedding()));
            }

            restClient.post()
                    .uri("/indexes/{name}/docs/index?api-version={v}", indexName, API_VERSION)
                    .body(Map.of("value", List.of(doc)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            System.err.println("Failed to upload incident to search: " + e.getMessage());
        }
    }

    /**
     * Returns up to {@code k} incidents nearest to the query vector, keeping only
     * those whose similarity score meets the configured {@code min-score}. This
     * prevents unrelated incidents from being surfaced as "matches" when nothing
     * in the index is genuinely similar.
     */
    public List<Incident> vectorSearch(double[] queryVector, int k) {
        try {
            Map<String, Object> vectorQuery = new LinkedHashMap<>();
            vectorQuery.put("kind", "vector");
            vectorQuery.put("vector", toList(queryVector));
            vectorQuery.put("fields", "embedding");
            vectorQuery.put("k", k);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("select", SELECT_FIELDS);
            body.put("vectorQueries", List.of(vectorQuery));

            return toIncidents(post(body), minScore);
        } catch (Exception e) {
            System.err.println("Vector search failed: " + e.getMessage());
            return List.of();
        }
    }

    /** Returns all stored incidents (capped) for inspection endpoints. */
    public List<Incident> listAll() {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("search", "*");
            body.put("top", 1000);
            body.put("select", SELECT_FIELDS);

            return toIncidents(post(body), 0.0);
        } catch (Exception e) {
            System.err.println("List incidents failed: " + e.getMessage());
            return List.of();
        }
    }

    /** Number of documents currently in the index. */
    public long count() {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("search", "*");
            body.put("count", true);
            body.put("top", 0);

            Map<String, Object> response = post(body);
            Object c = response == null ? null : response.get("@odata.count");
            return c instanceof Number n ? n.longValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(Map<String, Object> body) {
        return restClient.post()
                .uri("/indexes/{name}/docs/search?api-version={v}", indexName, API_VERSION)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    private Map<String, Object> buildIndexSchema() {
        Map<String, Object> hnsw = Map.of("name", "hnsw-algo", "kind", "hnsw");
        Map<String, Object> profile =
                Map.of("name", VECTOR_PROFILE, "algorithm", "hnsw-algo");
        Map<String, Object> vectorSearch = Map.of(
                "algorithms", List.of(hnsw),
                "profiles", List.of(profile));

        List<Map<String, Object>> fields = List.of(
                field("id", "Edm.String", Map.of("key", true, "filterable", true)),
                field("failureType", "Edm.String",
                        Map.of("searchable", true, "filterable", true)),
                field("summary", "Edm.String", Map.of("searchable", true)),
                field("rootCause", "Edm.String", Map.of("searchable", true)),
                field("suggestedFix", "Edm.String", Map.of("searchable", true)),
                field("severity", "Edm.String", Map.of()),
                field("jiraKey", "Edm.String", Map.of()),
                field("createdAt", "Edm.String", Map.of()),
                vectorField());

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", indexName);
        schema.put("fields", fields);
        schema.put("vectorSearch", vectorSearch);
        return schema;
    }

    private Map<String, Object> field(String name, String type, Map<String, Object> extra) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("type", type);
        f.putAll(extra);
        return f;
    }

    private Map<String, Object> vectorField() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", "embedding");
        f.put("type", "Collection(Edm.Single)");
        f.put("searchable", true);
        f.put("dimensions", EMBEDDING_DIMENSIONS);
        f.put("vectorSearchProfile", VECTOR_PROFILE);
        return f;
    }

    private List<Double> toList(double[] vector) {
        List<Double> list = new ArrayList<>(vector.length);
        for (double d : vector) {
            list.add(d);
        }
        return list;
    }

    private String documentId(Incident incident) {
        String key = incident.getJiraKey();
        if (key == null || key.isBlank()) {
            return UUID.randomUUID().toString();
        }
        // Document keys allow only letters, digits, dash, underscore, equals.
        return key.replaceAll("[^A-Za-z0-9_\\-=]", "_");
    }

    private List<Incident> toIncidents(Map<String, Object> response, double minScore) {
        List<Incident> result = new ArrayList<>();
        if (response == null || !(response.get("value") instanceof List<?> rows)) {
            return result;
        }
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            double score = map.get("@search.score") instanceof Number n
                    ? n.doubleValue() : Double.NaN;
            if (minScore > 0 && !Double.isNaN(score) && score < minScore) {
                System.out.println("[MEMORY] Dropping weak match jira="
                        + str(map.get("jiraKey")) + " score=" + score
                        + " (< min-score=" + minScore + ")");
                continue;
            }
            if (minScore > 0) {
                System.out.println("[MEMORY] Keeping match jira="
                        + str(map.get("jiraKey")) + " score=" + score);
            }
            Incident incident = new Incident();
            incident.setFailureType(str(map.get("failureType")));
            incident.setSummary(str(map.get("summary")));
            incident.setRootCause(str(map.get("rootCause")));
            incident.setSuggestedFix(str(map.get("suggestedFix")));
            incident.setSeverity(str(map.get("severity")));
            incident.setJiraKey(str(map.get("jiraKey")));
            String created = str(map.get("createdAt"));
            if (created != null && !created.isBlank()) {
                try {
                    incident.setCreatedAt(Instant.parse(created));
                } catch (Exception ignored) {
                    // Leave createdAt null if it cannot be parsed.
                }
            }
            result.add(incident);
        }
        return result;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
