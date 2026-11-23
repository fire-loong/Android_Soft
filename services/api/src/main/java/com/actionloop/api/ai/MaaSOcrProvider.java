package com.actionloop.api.ai;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 讯飞 MaaS OpenAI 兼容图像 OCR。只请求并接收纯文本，不虚构坐标。 */
@Component
public class MaaSOcrProvider implements OcrProvider {

    public static final String PROMPT_VERSION = "maas-image-ocr-text-v1";

    private static final Logger log = LoggerFactory.getLogger(MaaSOcrProvider.class);
    private static final String SYSTEM_PROMPT = """
            你是 OCR 文字识别器。只转写图片中真实存在的文字，按自然阅读顺序输出纯文本。
            不解释、不翻译、不补写、不输出 Markdown，也不要编造坐标或布局信息。
            """;

    private final OcrProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public MaaSOcrProvider(OcrProperties props, ObjectMapper objectMapper) {
        this(props, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
    }

    MaaSOcrProvider(OcrProperties props, ObjectMapper objectMapper, HttpClient httpClient) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String recognize(OcrInput input) {
        URI endpoint = URI.create(trimSlash(props.getBaseUrl()) + "/chat/completions");
        requireSecureTransport(endpoint);
        requireCredentials(endpoint);
        String traceId = UUID.randomUUID().toString();
        HttpResponse<String> response = exchange(endpoint, buildRequest(endpoint, input, traceId));
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new BusinessException(ErrorCode.OCR_OUTPUT_INVALID, "OCR 响应缺少纯文本内容");
            }
            return content.asText().trim();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OCR_OUTPUT_INVALID, "OCR 响应信封不是有效 JSON");
        }
    }

    private HttpRequest buildRequest(URI endpoint, OcrInput input, String traceId) {
        try {
            String dataUrl = "data:" + input.mime() + ";base64,"
                    + Base64.getEncoder().encodeToString(input.content());
            List<Map<String, Object>> userContent = List.of(
                    Map.of("type", "text", "text", "请完整识别这张图片中的文字，只返回识别文本。"),
                    Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", input.model());
            body.put("temperature", 0);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", userContent)));
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("X-Trace-Id", traceId)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            String apiKey = props.getApiKey() == null ? "" : props.getApiKey().trim();
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            return builder.build();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OCR_OUTPUT_INVALID, "无法序列化 OCR 请求");
        }
    }

    private HttpResponse<String> exchange(URI endpoint, HttpRequest request) {
        int timeoutRetries = 0;
        HttpResponse<String> response;
        while (true) {
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                break;
            } catch (HttpTimeoutException e) {
                if (timeoutRetries >= 1) {
                    throw new BusinessException(ErrorCode.OCR_UNAVAILABLE, "OCR 请求超时");
                }
                timeoutRetries++;
                backoff(timeoutRetries);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.OCR_UNAVAILABLE, "OCR 请求已中断");
            } catch (IOException e) {
                log.warn("OCR provider I/O failure: {}", e.getClass().getSimpleName());
                throw new BusinessException(ErrorCode.OCR_UNAVAILABLE, "OCR 服务暂不可用");
            }
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("OCR provider http status={}", response.statusCode());
            throw new BusinessException(ErrorCode.OCR_UNAVAILABLE, "OCR 服务暂不可用");
        }
        return response;
    }

    private void requireSecureTransport(URI endpoint) {
        if (!isLoopback(endpoint) && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new BusinessException(ErrorCode.OCR_UNAVAILABLE, "远端云端 OCR 必须使用 HTTPS");
        }
    }

    private void requireCredentials(URI endpoint) {
        String apiKey = props.getApiKey() == null ? "" : props.getApiKey().trim();
        if (apiKey.isBlank() && !isLoopback(endpoint)) {
            throw new BusinessException(ErrorCode.OCR_UNAVAILABLE, "未配置云端 OCR API Key");
        }
    }

    private void backoff(int retryNumber) {
        try {
            long delay = Math.max(1, props.getRetryBackoffMillis()) * (1L << (retryNumber - 1));
            Thread.sleep(Math.min(delay, 2000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.OCR_UNAVAILABLE, "OCR 重试已中断");
        }
    }

    private static boolean isLoopback(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().replace("[", "").replace("]", "");
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
