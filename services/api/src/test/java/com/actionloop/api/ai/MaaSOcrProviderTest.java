package com.actionloop.api.ai;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class MaaSOcrProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsRequestedModelAndPrivateImageAsDataUrlAndReturnsPlainText() {
        AtomicInteger calls = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        startServer(call -> envelope("请于11月30日18:00前提交课程设计PDF"), calls, bodies, 200, 0);
        MaaSOcrProvider provider = provider(5);
        byte[] image = new byte[]{1, 2, 3, 4};

        String text = provider.recognize(input("xoppaddleocrv16", image));

        assertEquals("请于11月30日18:00前提交课程设计PDF", text);
        assertEquals(1, calls.get());
        assertTrue(bodies.getFirst().contains("\"model\":\"xoppaddleocrv16\""));
        assertTrue(bodies.getFirst().contains("data:image/png;base64," + Base64.getEncoder().encodeToString(image)));
        assertFalse(bodies.getFirst().contains("bbox"));
        assertFalse(bodies.getFirst().contains("response_format"));
    }

    @Test
    void non2xxFailsWithoutSwitchingModel() {
        AtomicInteger calls = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        startServer(call -> envelope("ignored"), calls, bodies, 500, 0);
        MaaSOcrProvider provider = provider(5);

        BusinessException error = assertThrows(BusinessException.class,
                () -> provider.recognize(input("xopdeepseekocr", new byte[]{1})));

        assertEquals(ErrorCode.OCR_UNAVAILABLE, error.code());
        assertEquals(1, calls.get());
        assertTrue(bodies.getFirst().contains("xopdeepseekocr"));
        assertFalse(bodies.getFirst().contains("xophunyuanocr"));
    }

    @Test
    void blankOrMalformedContentIsExplicitOutputFailure() {
        AtomicInteger calls = new AtomicInteger();
        startServer(call -> envelope("  "), calls, null, 200, 0);
        MaaSOcrProvider provider = provider(5);

        BusinessException error = assertThrows(BusinessException.class,
                () -> provider.recognize(input("xophunyuanocr", new byte[]{1})));

        assertEquals(ErrorCode.OCR_OUTPUT_INVALID, error.code());
        assertEquals(1, calls.get());
    }

    @Test
    void retriesTimeoutOnceThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        startServer(call -> envelope("识别文本"), calls, null, 200, 1200);
        MaaSOcrProvider provider = provider(1);

        String text = provider.recognize(input("xoppaddleocrv16", new byte[]{1}));

        assertEquals("识别文本", text);
        assertEquals(2, calls.get());
    }

    @Test
    void rejectsPlaintextRemoteEndpointBeforeSendingCredentials() {
        OcrProperties props = new OcrProperties();
        props.setBaseUrl("http://ocr.example.com/v2");
        props.setApiKey("test-only-key");
        HttpClient httpClient = mock(HttpClient.class);
        MaaSOcrProvider provider = new MaaSOcrProvider(props, objectMapper, httpClient);

        BusinessException error = assertThrows(BusinessException.class,
                () -> provider.recognize(input("xoppaddleocrv16", new byte[]{1})));

        assertEquals(ErrorCode.OCR_UNAVAILABLE, error.code());
        verifyNoInteractions(httpClient);
    }

    private OcrProvider.OcrInput input(String model, byte[] image) {
        return new OcrProvider.OcrInput(image, "image/png", model);
    }

    private MaaSOcrProvider provider(int timeoutSeconds) {
        OcrProperties props = new OcrProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v2");
        props.setApiKey("");
        props.setTimeoutSeconds(timeoutSeconds);
        props.setRetryBackoffMillis(1);
        return new MaaSOcrProvider(props, objectMapper);
    }

    private void startServer(ResponseFactory factory, AtomicInteger calls, List<String> bodies,
                             int status, long firstDelayMs) {
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
                byte[] bytes = factory.body(call).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                try {
                    exchange.sendResponseHeaders(status, bytes.length);
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

    @FunctionalInterface
    private interface ResponseFactory {
        String body(int call);
    }
}
