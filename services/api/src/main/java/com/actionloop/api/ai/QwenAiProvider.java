package com.actionloop.api.ai;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * OpenAI 兼容结构化 Provider（讯飞 MaaS、通义千问兼容端点等）。
 * 网络超时最多退避重试一次；Schema/JSON 错误最多修复一次；无原文引用直接拒绝，不靠重试补事实。
 */
@Component
public class QwenAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(QwenAiProvider.class);
    public static final String PROMPT_VERSION = "openai-notification-extract-v2";
    public static final String SCHEMA_VERSION = "notification-task-v1";

    private static final String SYSTEM_PROMPT = """
            你是“日常生活行动系统”的事项信息抽取器，不是聊天助手。只依据用户 INPUT 中的原文抽取。
            不补充原文不存在的时间、地点、费用、材料或政策。每个事实必须给出原文连续子串 evidenceQuotes。
            相对日期只放 due.rawText，不自行猜算绝对时间；due.resolvedAt 始终输出 null，由服务端程序复核。
            多日期无法确定关系时全部写入 uncertainties，due.rawText 为 null，needsUserConfirmation 必须为 true。
            验证码、密码、支付和敏感身份内容不输出其值，直接返回空 candidates。
            无法抽取时返回空 candidates。只输出符合给定 JSON Schema 的 JSON，不要 Markdown。
            Schema：{"schemaVersion":"notification-task-v1","candidates":[{
            "isActionable":true,"title":"简短标题","summary":"摘要或null",
            "category":"SUBMISSION|REGISTRATION|PAYMENT|EVENT|TASK",
            "due":{"rawText":"原文日期连续子串或null","resolvedAt":null,"precision":"NONE","confidence":0.0},
            "location":"地点或null","materials":[{"name":"材料","quote":"原文连续子串"}],
            "action":"下一步或null","confidence":0.0,
            "fieldConfidence":{"title":0.0,"due":0.0,"time":0.0,"location":0.0,"materials":0.0},
            "evidenceQuotes":["原文连续子串"],"needsUserConfirmation":true,
            "uncertainties":[],"sensitive":false,"reasonCode":null}]}
            """;

    private final AiProperties props;
    private final ObjectMapper objectMapper;
    private final RuleExtractor ruleExtractor;
    private final HttpClient httpClient;
    private final JsonSchema outputSchema;

    @Autowired
    public QwenAiProvider(AiProperties props, ObjectMapper objectMapper, RuleExtractor ruleExtractor) {
        this(props, objectMapper, ruleExtractor, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
    }

    QwenAiProvider(AiProperties props, ObjectMapper objectMapper, RuleExtractor ruleExtractor,
                   HttpClient httpClient) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.ruleExtractor = ruleExtractor;
        this.httpClient = httpClient;
        try (InputStream schema = QwenAiProvider.class.getResourceAsStream(
                "/ai/notification-task-v1.schema.json")) {
            if (schema == null) {
                throw new IllegalStateException("Missing notification extraction JSON Schema");
            }
            this.outputSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schema);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load notification extraction JSON Schema", e);
        }
    }

    @Override
    public String name() {
        return "maas";
    }

    @Override
    public boolean production() {
        return true;
    }

    @Override
    public CandidateExtraction extract(ExtractionRequest request) {
        String traceId = request.traceId() == null || request.traceId().isBlank()
                ? UUID.randomUUID().toString() : request.traceId();
        return generateStructured(SCHEMA_VERSION, request,
                Duration.ofSeconds(props.getTimeoutSeconds()), traceId);
    }

    @Override
    public CandidateExtraction generateStructured(String schemaVersion, ExtractionRequest input,
                                                  Duration timeout, String traceId) {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的 AI 输出 Schema");
        }
        URI endpoint = URI.create(trimSlash(props.getBaseUrl()) + "/chat/completions");
        requireCredentials(endpoint);
        String initial = exchange(endpoint, buildRequest(endpoint, input, timeout, traceId, null, null), true);
        try {
            return parse(initial, input);
        } catch (FormatException first) {
            log.info("AI output requires one schema repair, traceId={}", traceId);
            String repaired = exchange(endpoint,
                    buildRequest(endpoint, input, timeout, traceId, initial, first.getMessage()), false);
            try {
                return parse(repaired, input);
            } catch (FormatException second) {
                throw new BusinessException(ErrorCode.AI_OUTPUT_INVALID, "AI 输出修复后仍不符合 Schema");
            }
        }
    }

    @Override
    public boolean health() {
        try {
            URI endpoint = URI.create(trimSlash(props.getBaseUrl()) + "/models");
            requireCredentials(endpoint);
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(Math.min(3, props.getTimeoutSeconds()))).GET();
            if (!props.getApiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + props.getApiKey());
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private HttpRequest buildRequest(URI endpoint, ExtractionRequest input, Duration timeout, String traceId,
                                     String previousOutput, String formatError) {
        try {
            String userContent = "来源 App：" + nullSafe(input.sourceApp())
                    + "\n接收时间：" + input.receivedAt()
                    + "\n时区：" + nullSafe(input.timezone())
                    + "\nINPUT 原文：\n" + input.text();
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
            messages.add(Map.of("role", "user", "content", userContent));
            if (previousOutput != null) {
                String repair = "上次输出未通过 JSON Schema。请仅修正格式并重新输出完整 JSON，不得新增事实。"
                        + "\n校验错误：" + abbreviate(formatError, 1000)
                        + "\n上次输出：\n" + abbreviate(previousOutput, 12000);
                messages.add(Map.of("role", "assistant", "content", previousOutput));
                messages.add(Map.of("role", "user", "content", repair));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", props.getModel());
            body.put("temperature", 0.1);
            body.put("response_format", Map.of("type", "json_object"));
            body.put("messages", messages);
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("X-Trace-Id", traceId)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            if (!props.getApiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + props.getApiKey());
            }
            return builder.build();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.AI_OUTPUT_INVALID, "无法序列化 AI 请求");
        }
    }

    private String exchange(URI endpoint, HttpRequest request, boolean allowTimeoutRetry) {
        int timeoutRetries = 0;
        HttpResponse<String> response;
        while (true) {
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                break;
            } catch (HttpTimeoutException e) {
                if (!allowTimeoutRetry || timeoutRetries >= 1) {
                    throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "AI 请求超时");
                }
                timeoutRetries++;
                backoff(timeoutRetries);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "AI 请求已中断");
            } catch (IOException e) {
                log.warn("AI provider I/O failure: {}", e.getClass().getSimpleName());
                throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "AI 服务暂不可用");
            }
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("AI provider http status={}", response.statusCode());
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "AI 服务暂不可用");
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                throw new BusinessException(ErrorCode.AI_OUTPUT_INVALID, "AI 响应缺少结构化内容");
            }
            return content.asText();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.AI_OUTPUT_INVALID, "AI 响应信封不是有效 JSON");
        }
    }

    private CandidateExtraction parse(String content, ExtractionRequest input) throws FormatException {
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (IOException e) {
            throw new FormatException("content is not valid JSON");
        }
        if (root == null) {
            throw new FormatException("content is empty");
        }
        Set<ValidationMessage> violations = outputSchema.validate(root);
        if (!violations.isEmpty()) {
            throw new FormatException(violations.iterator().next().getMessage());
        }
        List<CandidateExtraction.CandidateDraft> drafts = new ArrayList<>();
        for (JsonNode node : root.path("candidates")) {
            if (!node.path("isActionable").asBoolean() || node.path("sensitive").asBoolean()) {
                continue;
            }
            List<String> quotes = strings(node.path("evidenceQuotes"));
            requireEvidence(input.text(), quotes);
            List<String> materials = new ArrayList<>();
            for (JsonNode material : node.path("materials")) {
                requireQuote(input.text(), material.path("quote").asText());
                materials.add(material.path("name").asText());
            }
            String rawDueText = text(node.path("due"), "rawText");
            if (rawDueText != null && !rawDueText.isBlank()) {
                requireQuote(input.text(), rawDueText);
            }
            List<String> uncertainties = new ArrayList<>(strings(node.path("uncertainties")));
            CandidateExtraction.CandidateDraft resolved = resolveDue(rawDueText, input);
            if (rawDueText != null && !rawDueText.isBlank() && resolved.dueAt() == null) {
                uncertainties.add("DUE_UNRESOLVED: 保留原始日期文本，不生成绝对截止时间");
            }
            if ((rawDueText == null || rawDueText.isBlank())
                    && text(node.path("due"), "resolvedAt") != null) {
                uncertainties.add("DUE_RESOLVED_AT_IGNORED: 缺少原始日期文本");
            }
            Map<String, Double> fieldConfidence = new LinkedHashMap<>();
            node.path("fieldConfidence").fields().forEachRemaining(e ->
                    fieldConfidence.put(e.getKey(), e.getValue().asDouble(0)));
            List<String> reasons = new ArrayList<>();
            reasons.add("EVIDENCE_QUOTES_VERIFIED");
            if (resolved.dueAt() != null) {
                reasons.add("DUE_PROGRAM_RESOLVED");
            }
            drafts.add(new CandidateExtraction.CandidateDraft(
                    node.path("title").asText(), text(node, "summary"), text(node, "category"),
                    rawDueText, resolved.dueAt(), resolved.dueDate(), resolved.dueTimezone(),
                    rawDueText == null || rawDueText.isBlank() ? "NONE" : resolved.timePrecision(),
                    text(node, "location"), materials, text(node, "action"),
                    node.path("confidence").asDouble(), fieldConfidence, reasons, quotes,
                    true, uncertainties
            ));
        }
        return new CandidateExtraction(drafts, props.getModel(), PROMPT_VERSION,
                SCHEMA_VERSION, "AI", List.of());
    }

    private CandidateExtraction.CandidateDraft resolveDue(String rawDueText, ExtractionRequest input) {
        if (rawDueText == null || rawDueText.isBlank()) {
            return new CandidateExtraction.CandidateDraft("", null, "TASK", null,
                    null, null, safeZone(input.timezone()), "NONE", null, List.of(), null,
                    0, Map.of(), List.of(), List.of(), true, List.of());
        }
        ExtractionRequest dueRequest = new ExtractionRequest(rawDueText, input.receivedAt(), input.timezone(),
                input.sourceApp(), input.sourceTitle(), input.workspaceId(), input.sourceItemId(),
                input.requestedBy(), input.traceId());
        return ruleExtractor.extract(dueRequest).candidates().getFirst();
    }

    private void requireEvidence(String source, List<String> quotes) {
        if (quotes.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_OUTPUT_INVALID, "AI 可行动候选缺少原文引用");
        }
        quotes.forEach(quote -> requireQuote(source, quote));
    }

    private static void requireQuote(String source, String quote) {
        if (quote == null || quote.isBlank() || source == null || !source.contains(quote)) {
            throw new BusinessException(ErrorCode.AI_OUTPUT_INVALID, "EVIDENCE_QUOTE_MISMATCH");
        }
    }

    private void requireCredentials(URI endpoint) {
        String apiKey = props.getApiKey() == null ? "" : props.getApiKey().trim();
        if (!apiKey.equals(props.getApiKey())) {
            props.setApiKey(apiKey);
        }
        if (apiKey.isBlank() && !isLoopback(endpoint)) {
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "未配置 AI API Key，已降级为本地规则");
        }
    }

    private void backoff(int retryNumber) {
        try {
            long delay = Math.max(1, props.getRetryBackoffMillis()) * (1L << (retryNumber - 1));
            Thread.sleep(Math.min(delay, 2000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE, "AI 重试已中断");
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean isLoopback(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().replace("[", "").replace("]", "");
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static String safeZone(String timezone) {
        try {
            return ZoneId.of(nullSafe(timezone).isBlank() ? "Asia/Shanghai" : timezone).getId();
        } catch (RuntimeException e) {
            return "Asia/Shanghai";
        }
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final class FormatException extends Exception {
        private FormatException(String message) {
            super(message);
        }
    }
}
