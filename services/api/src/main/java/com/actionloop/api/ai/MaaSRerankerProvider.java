package com.actionloop.api.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 真实 MaaS rerank 调用。返回结果保持输入 documents 的 index，便于重排候选。 */
@Component
public class MaaSRerankerProvider {

    private static final Logger log = LoggerFactory.getLogger(MaaSRerankerProvider.class);
    private static final int MAX_DOC_CHARS = 1200;

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MaaSRerankerProvider(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .build();
    }

    public boolean available() {
        return "maas".equals(properties.getProvider())
                && properties.isRerankEnabled()
                && properties.getApiKey() != null && !properties.getApiKey().isBlank()
                && properties.getRerankModel() != null && !properties.getRerankModel().isBlank();
    }

    public List<RerankHit> rerank(String query, List<String> documents, int topN) {
        List<RerankHit> empty = identity(documents);
        if (!available() || query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return empty;
        }
        try {
            List<String> safeDocs = documents.stream().map(this::truncate).toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", properties.getRerankModel());
            payload.put("query", truncate(query));
            payload.put("documents", safeDocs);
            payload.put("top_n", Math.max(1, Math.min(topN, safeDocs.size())));
            payload.put("return_documents", false);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl().replaceAll("/+$", "") + "/rerank"))
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("rerank request failed status={}", response.statusCode());
                return empty;
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return empty;
            }
            List<RerankHit> hits = new ArrayList<>();
            for (JsonNode result : results) {
                hits.add(new RerankHit(result.path("index").asInt(-1), result.path("relevance_score").asDouble(0d)));
            }
            return hits;
        } catch (Exception e) {
            log.warn("rerank call failed code={}", e.getClass().getSimpleName());
            return empty;
        }
    }

    private List<RerankHit> identity(List<String> documents) {
        List<RerankHit> hits = new ArrayList<>();
        if (documents != null) {
            for (int i = 0; i < documents.size(); i++) {
                hits.add(new RerankHit(i, 0d));
            }
        }
        return hits;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_DOC_CHARS ? text.substring(0, MAX_DOC_CHARS) : text;
    }

    public record RerankHit(int index, double score) {
    }
}
