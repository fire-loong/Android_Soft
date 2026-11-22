package com.actionloop.api.ai;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

/**
 * 通义千问兼容接口 Provider（OpenAI chat/completions 兼容）。
 * 失败、超时或 JSON 不合格时抛出确定错误码，由上层降级到本地规则；不得用固定答案冒充 AI。
 */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "qwen")
public class QwenAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(QwenAiProvider.class);
    public static final String PROMPT_VERSION = "qwen-extract-v1";

    private static final String SYSTEM_PROMPT = """
            你是“日常生活行动系统”的信息抽取器。只从用户给出的原文提取候选事项，禁止补充原文没有的事实。
            输出严格 JSON（不要 Markdown 代码块），结构：
            {"candidates":[{"title":"简短可执行标题","summary":"原文摘要","category":"SUBMISSION|REGISTRATION|PAYMENT|EVENT|TASK",
            "dueAt":"带时区的 ISO-8601 时间或 null","dueDate":"yyyy-MM-dd 或 null","dueTimezone":"IANA 时区或 null",
            "timePrecision":"MINUTE|DATE|NONE","location":"地点或 null","materials":["材料"],"nextAction":"下一步或 null",
            "confidence":0.0,"fieldConfidence":{"title":0.0,"due":0.0,"time":0.0,"location":0.0,"materials":0.0},
            "reasons":["依据"]}]}
            若存在多个互相冲突的日期，dueAt/dueDate 必须为 null，reasons 写 MULTIPLE_DATES 并列出候选；
            若原文没有日期不得虚构；验证码、支付、密码、身份证等敏感内容直接返回空 candidates。
            """;

    private final AiProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public QwenAiProvider(AiProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String name() {
        return "qwen";
    }

    @Override
    public boolean production() {
        return true;
    }

    @Override
    public CandidateExtraction extract(ExtractionRequest request) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "未配置 AI API Key，已降级为本地规则");
        }
        try {
            Map<String, Object> system = Map.of("role", "system", "content", SYSTEM_PROMPT);
            String userContent = "来源 App：" + nullSafe(request.sourceApp())
                    + "\n接收时间：" + request.receivedAt()
                    + "\n时区：" + nullSafe(request.timezone())
                    + "\n原文：\n" + request.text();
            Map<String, Object> user = Map.of("role", "user", "content", userContent);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", props.getModel());
            body.put("temperature", 0.1);
            body.put("response_format", Map.of("type", "json_object"));
            body.put("messages", List.of(system, user));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(props.getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.warn("AI provider http status={}", response.statusCode());
                throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "AI 服务暂不可用");
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            return parse(content);
        } catch (BusinessException be) {
            throw be;
        } catch (java.io.InterruptedIOException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "AI 请求超时");
        } catch (Exception e) {
            log.warn("AI provider failure: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "AI 服务暂不可用");
        }
    }

    private CandidateExtraction parse(String content) throws Exception {
        String json = content.trim();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                json = json.substring(firstNewline + 1, lastFence).trim();
            }
        }
        JsonNode root = objectMapper.readTree(json);
        JsonNode list = root.path("candidates");
        if (!list.isArray()) {
            throw new BusinessException(ErrorCode.AI_OUTPUT_INVALID, "AI 输出缺少 candidates 数组");
        }
        List<CandidateExtraction.CandidateDraft> drafts = new ArrayList<>();
        for (JsonNode node : list) {
            String title = text(node, "title");
            if (title == null || title.isBlank()) {
                continue;
            }
            List<String> materials = new ArrayList<>();
            node.path("materials").forEach(m -> {
                if (m.isTextual() && !m.asText().isBlank()) {
                    materials.add(m.asText());
                }
            });
            Map<String, Double> fieldConfidence = new LinkedHashMap<>();
            node.path("fieldConfidence").fields().forEachRemaining(e ->
                    fieldConfidence.put(e.getKey(), e.getValue().asDouble(0)));
            List<String> reasons = new ArrayList<>();
            node.path("reasons").forEach(r -> {
                if (r.isTextual()) {
                    reasons.add(r.asText());
                }
            });
            drafts.add(new CandidateExtraction.CandidateDraft(
                    title,
                    text(node, "summary"),
                    text(node, "category"),
                    text(node, "dueAt"),
                    text(node, "dueDate"),
                    text(node, "dueTimezone"),
                    text(node, "timePrecision"),
                    text(node, "location"),
                    materials,
                    text(node, "nextAction"),
                    node.path("confidence").asDouble(0.5),
                    fieldConfidence,
                    reasons
            ));
        }
        return new CandidateExtraction(drafts, props.getModel(), PROMPT_VERSION, "AI", List.of());
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
