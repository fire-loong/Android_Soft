package com.actionloop.api.ai;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
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
import java.util.LinkedHashMap;
import java.util.Map;

/** 真实 MaaS OpenAI 兼容 embeddings 调用。实测 xop3qwen8bembedding 返回 768 维。 */
@Component
public class MaaSEmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(MaaSEmbeddingProvider.class);
    private static final int MAX_INPUT_CHARS = 8000;

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MaaSEmbeddingProvider(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .build();
    }

    public boolean available() {
        return "maas".equals(properties.getProvider())
                && properties.isEmbeddingEnabled()
                && properties.getApiKey() != null && !properties.getApiKey().isBlank()
                && properties.getEmbeddingModel() != null && !properties.getEmbeddingModel().isBlank();
    }

    public float[] embed(String text) {
        if (!available()) {
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "embedding provider unavailable");
        }
        String input = truncate(text);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", properties.getEmbeddingModel());
            payload.put("input", input);
            payload.put("encoding_format", "float");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl().replaceAll("/+$", "") + "/embeddings"))
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("embedding request failed status={}", response.statusCode());
                throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "embedding request failed");
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embeddingNode = root.path("data").path(0).path("embedding");
            if (!embeddingNode.isArray() || embeddingNode.isEmpty()) {
                throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "embedding response invalid");
            }
            float[] embedding = new float[embeddingNode.size()];
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] = (float) embeddingNode.get(i).asDouble();
            }
            if (embedding.length != properties.getEmbeddingDim()) {
                throw new BusinessException(ErrorCode.AI_UNAVAILABLE,
                        "embedding dim mismatch expected=" + properties.getEmbeddingDim() + " actual=" + embedding.length);
            }
            return embedding;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("embedding call failed code={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "embedding call failed");
        }
    }

    public String toVectorLiteral(float[] embedding) {
        StringBuilder builder = new StringBuilder(embedding.length * 12 + 2);
        builder.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(embedding[i]);
        }
        builder.append(']');
        return builder.toString();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
    }
}
