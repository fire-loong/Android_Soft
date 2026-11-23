package com.actionloop.api.ai;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QwenAiProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void validatesSchemaEvidenceAndResolvesDateByProgram() {
        AtomicInteger calls = new AtomicInteger();
        startServer(() -> envelope(validContent()), calls, null);
        QwenAiProvider provider = provider(5);

        CandidateExtraction result = provider.extract(request());
        CandidateExtraction.CandidateDraft candidate = result.candidates().getFirst();

        assertEquals(1, calls.get());
        assertEquals("11月30日18:00前", candidate.rawDueText());
        assertEquals("2026-11-30T10:00:00Z", candidate.dueAt());
        assertEquals("2026-11-30", candidate.dueDate());
        assertEquals("MINUTE", candidate.timePrecision());
        assertTrue(candidate.reasons().contains("DUE_PROGRAM_RESOLVED"));
        assertFalse(candidate.dueAt().contains("2099"));
        assertEquals(QwenAiProvider.SCHEMA_VERSION, result.schemaVersion());
    }

    @Test
    void retriesMalformedModelContentExactlyOnceWithRepairPrompt() {
        AtomicInteger calls = new AtomicInteger();
        List<String> requestBodies = new ArrayList<>();
        startServer(() -> calls.get() == 1 ? envelope("not-json") : envelope(validContent()), calls, requestBodies);
        QwenAiProvider provider = provider(5);

        CandidateExtraction result = provider.extract(request());

        assertEquals(1, result.candidates().size());
        assertEquals(2, calls.get());
        assertTrue(requestBodies.get(1).contains("上次输出未通过 JSON Schema"));
    }

    @Test
    void rejectsQuoteOutsideSourceWithoutRepair() {
        AtomicInteger calls = new AtomicInteger();
        String invalid = validContent().replace("请各位同学于11月30日18:00前在课程平台提交课程设计PDF。",
                "原文中不存在的引用");
        startServer(() -> envelope(invalid), calls, null);
        QwenAiProvider provider = provider(5);

        BusinessException error = assertThrows(BusinessException.class, () -> provider.extract(request()));

        assertEquals(ErrorCode.AI_OUTPUT_INVALID, error.code());
        assertEquals(1, calls.get(), "无依据输出属于事实校验失败，不得做格式修复重试");
    }

    @Test
    void retriesTimeoutOnceThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        startServer(() -> envelope(validContent()), calls, null, 1200);
        QwenAiProvider provider = provider(1);

        CandidateExtraction result = provider.extract(request());

        assertEquals(1, result.candidates().size());
        assertEquals(2, calls.get());
    }

    @Test
    void blankKeyIsOnlyAcceptedForLoopback() {
        AiProperties props = properties("https://example.com/v2", 1);
        QwenAiProvider provider = new QwenAiProvider(props, objectMapper, new RuleExtractor());

        BusinessException error = assertThrows(BusinessException.class, () -> provider.extract(request()));

        assertEquals(ErrorCode.AI_UNAVAILABLE, error.code());
    }

    private QwenAiProvider provider(int timeoutSeconds) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v2";
        return new QwenAiProvider(properties(baseUrl, timeoutSeconds), objectMapper, new RuleExtractor());
    }

    private AiProperties properties(String baseUrl, int timeoutSeconds) {
        AiProperties props = new AiProperties();
        props.setProvider("maas");
        props.setBaseUrl(baseUrl);
        props.setApiKey("");
        props.setModel("spark-x2.6-test");
        props.setTimeoutSeconds(timeoutSeconds);
        props.setRetryBackoffMillis(1);
        return props;
    }

    private void startServer(ResponseFactory factory, AtomicInteger calls, List<String> bodies) {
        startServer(factory, calls, bodies, 0);
    }

    private void startServer(ResponseFactory factory, AtomicInteger calls, List<String> bodies, long firstDelayMs) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v2/chat/completions", exchange -> {
                int call = calls.incrementAndGet();
                if (bodies != null) {
                    bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                }
                if (call == 1 && firstDelayMs > 0) {
                    try {
                        Thread.sleep(firstDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                String body = call == 1 && firstDelayMs > 0 ? envelope(validContent()) : factory.body();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                try {
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) {
                    // 超时测试会主动断开第一次响应。
                } finally {
                    exchange.close();
                }
            });
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String envelope(String content) {
        try {
            return objectMapper.writeValueAsString(Map.of("choices", List.of(
                    Map.of("message", Map.of("content", content)))));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ExtractionRequest request() {
        return new ExtractionRequest("请各位同学于11月30日18:00前在课程平台提交课程设计PDF。",
                Instant.parse("2026-11-22T02:00:00Z"), "Asia/Shanghai", "班级群", "通知",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-test");
    }

    private static String validContent() {
        return """
                {"schemaVersion":"notification-task-v1","candidates":[{
                  "isActionable":true,
                  "title":"提交课程设计PDF",
                  "summary":"在课程平台提交课程设计PDF",
                  "category":"SUBMISSION",
                  "due":{"rawText":"11月30日18:00前","resolvedAt":"2099-01-01T00:00:00Z","precision":"MINUTE","confidence":0.99},
                  "location":null,
                  "materials":[{"name":"课程设计PDF","quote":"课程设计PDF"}],
                  "action":"在课程平台提交课程设计PDF",
                  "confidence":0.95,
                  "fieldConfidence":{"title":0.9,"due":0.99,"time":0.99,"location":0.0,"materials":0.9},
                  "evidenceQuotes":["请各位同学于11月30日18:00前在课程平台提交课程设计PDF。"],
                  "needsUserConfirmation":true,
                  "uncertainties":[],
                  "sensitive":false,
                  "reasonCode":null
                }]}
                """;
    }

    @FunctionalInterface
    private interface ResponseFactory {
        String body();
    }
}
