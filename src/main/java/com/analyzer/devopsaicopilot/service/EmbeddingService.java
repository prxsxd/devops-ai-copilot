package com.analyzer.devopsaicopilot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Produces semantic embeddings via Azure OpenAI (text-embedding-3-small) for
 * the incident-memory RAG.
 *
 * <p>The embedding endpoint is configured independently of the chat endpoint
 * (via {@code azure.openai.embedding-endpoint}) so it can be the exact base URL
 * the embeddings deployment expects, with no URL rewriting.
 */
@Slf4j
@Service
public class EmbeddingService {

    private static final String API_VERSION = "2024-10-21";

    private final RestClient restClient;
    private final String deployment;
    private final String baseUrl;

    public EmbeddingService(
            @Value("${azure.openai.embedding-endpoint}") String endpoint,
            @Value("${azure.openai.key}") String apiKey,
            @Value("${azure.openai.embedding-deployment}") String deployment) {

        String base = endpoint == null ? "" : endpoint.trim();
        this.baseUrl = base;

        this.restClient = RestClient.builder()
                .baseUrl(base)
                .defaultHeader("api-key", apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.deployment = deployment;

        log.info("[EMBED] Configured endpoint='{}', deployment='{}', api-version='{}'",
                base, deployment, API_VERSION);
        log.info("[EMBED] Full URL = {}/openai/deployments/{}/embeddings?api-version={}",
                base, deployment, API_VERSION);
    }

    /**
     * Returns the embedding vector for the given text, or {@code null} if the
     * input is empty or the call fails.
     */
    public double[] embed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            EmbeddingResponse response = restClient.post()
                    .uri("/openai/deployments/{deployment}/embeddings?api-version={version}",
                            deployment, API_VERSION)
                    .body(Map.of("input", text))
                    .retrieve()
                    .body(EmbeddingResponse.class);

            if (response == null || response.data() == null || response.data().isEmpty()) {
                return null;
            }
            return response.data().get(0).embedding();
        } catch (Exception e) {
            log.error("[EMBED] Call failed for URL {}/openai/deployments/{}/embeddings?api-version={} : {}",
                    baseUrl, deployment, API_VERSION, e.getMessage());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<Item> data) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Item(double[] embedding) {
        }
    }
}
