package com.actionloop.api.service;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-04 SSRF 抓取防护单测（指导书第 408 行、1223 行、1333 行）。
 * 传输层以假的 {@link SourceFetchService.HttpTransport} 注入，不访问公网；地址校验走真实 DNS/IP 解析。
 */
class SourceFetchServiceTest {

    private static final String PAGE = "https://93.184.216.34/notice";
    private static final String HOST = "93.184.216.34";

    private final FakeTransport transport = new FakeTransport();
    private final long[] clock = {1_000_000_000_000L};

    private SourceFetchService service(boolean allowHttp, boolean respectRobots) {
        return new SourceFetchService(transport, allowHttp, respectRobots, 1000, () -> clock[0]);
    }

    @Test
    void blocksLoopbackPrivateLinkLocalAndIpv6InternalAddresses() {
        SourceFetchService service = service(false, true);
        List<String> urls = List.of(
                "https://127.0.0.1/x",
                "https://localhost/x",
                "https://10.0.0.8/x",
                "https://172.16.5.5/x",
                "https://192.168.1.10/x",
                "https://169.254.169.254/latest/meta-data/",
                "https://[::1]/x",
                "https://[fd00::1]/x",
                "https://[fe80::1]/x",
                "https://0.0.0.0/x",
                "https://[::ffff:127.0.0.1]/x");

        for (String url : urls) {
            SourceFetchService.BlockedException e = assertThrows(SourceFetchService.BlockedException.class,
                    () -> service.fetch(url), url);
            assertEquals("internal_address", e.reason(), url);
        }
        assertTrue(transport.calls.isEmpty(), "被拒绝的地址不得发起任何网络请求");
    }

    @Test
    void blocksIpv6TransitionFormsThatEmbedPrivateIpv4() {
        SourceFetchService service = service(false, true);
        List<String> urls = List.of(
                "https://[::10.0.0.1]/x",
                "https://[::a9fe:a9fe]/x",
                "https://[64:ff9b::a00:1]/x",
                "https://[2002:0a00:0001::1]/x",
                "https://[2001:0::1]/x");

        for (String url : urls) {
            SourceFetchService.BlockedException e = assertThrows(SourceFetchService.BlockedException.class,
                    () -> service.fetch(url), url);
            assertEquals("internal_address", e.reason(), url);
        }
        assertTrue(transport.calls.isEmpty());
    }

    @Test
    void rejectsMalformedHostsAsPolicyViolation() {
        SourceFetchService service = service(false, true);
        for (String url : List.of("https://127.1/x", "https://%31%32%37.0.0.1/x", "https:///x")) {
            SourceFetchService.BlockedException e = assertThrows(SourceFetchService.BlockedException.class,
                    () -> service.fetch(url), url);
            assertEquals("host_missing", e.reason(), url);
        }
        assertTrue(transport.calls.isEmpty());
    }

    @Test
    void blocksNonHttpProtocolsBeforeAnyNetworkAccess() {
        SourceFetchService service = service(false, true);
        for (String url : List.of("file:///etc/passwd", "ftp://example.com/a",
                "gopher://example.com/", "javascript:alert(1)")) {
            SourceFetchService.BlockedException e = assertThrows(SourceFetchService.BlockedException.class,
                    () -> service.fetch(url), url);
            assertEquals("protocol_not_allowed", e.reason(), url);
        }
        assertTrue(transport.calls.isEmpty());
    }

    @Test
    void blocksPlainHttpUnlessExplicitlyAllowed() {
        SourceFetchService service = service(false, false);
        SourceFetchService.BlockedException e = assertThrows(SourceFetchService.BlockedException.class,
                () -> service.fetch("http://93.184.216.34/notice"));
        assertEquals("protocol_not_allowed", e.reason());
    }

    @Test
    void blocksUserInfoAndNonDefaultPorts() {
        SourceFetchService service = service(false, false);

        SourceFetchService.BlockedException userInfo = assertThrows(SourceFetchService.BlockedException.class,
                () -> service.fetch("https://user:pw@93.184.216.34/x"));
        assertEquals("userinfo_forbidden", userInfo.reason());

        SourceFetchService.BlockedException customPort = assertThrows(SourceFetchService.BlockedException.class,
                () -> service.fetch("https://93.184.216.34:8443/x"));
        assertEquals("port_not_allowed", customPort.reason());

        SourceFetchService.BlockedException httpPortOnHttps = assertThrows(SourceFetchService.BlockedException.class,
                () -> service.fetch("https://93.184.216.34:80/x"));
        assertEquals("port_not_allowed", httpPortOnHttps.reason());

        assertTrue(transport.calls.isEmpty());
    }

    @Test
    void fetchesHtmlAndExtractsPlainTextWithHash() {
        SourceFetchService service = service(false, true);
        transport.on(PAGE, 200, "text/html; charset=utf-8",
                "<html><head><script>steal()</script></head><body>"
                        + "<p>请于2027年12月30日18:00前提交助学金申请表</p></body></html>");

        SourceFetchService.Fetched fetched = service.fetch(PAGE);

        assertEquals(PAGE, fetched.finalUrl());
        assertEquals(200, fetched.statusCode());
        assertTrue(fetched.text().contains("请于2027年12月30日18:00前提交助学金申请表"));
        assertFalse(fetched.text().contains("steal"), "脚本内容必须被剥离");
        assertEquals(64, fetched.contentHash().length());
        assertEquals(SourceFetchService.sha256(fetched.text()), fetched.contentHash());
    }

    @Test
    void followsRedirectOnlyAfterRevalidatingEveryHop() {
        SourceFetchService service = service(false, true);
        String target = "https://93.184.216.35/final";
        transport.redirect(PAGE, 302, target)
                .on(target, 200, "text/plain; charset=utf-8", "官方通知：材料增加身份证复印件");

        SourceFetchService.Fetched fetched = service.fetch(PAGE);

        assertEquals(target, fetched.finalUrl(), "必须报告重定向后的最终地址");
        assertEquals(200, fetched.statusCode());
        assertTrue(fetched.text().contains("身份证复印件"));
    }

    @Test
    void resolvesRelativeRedirectAgainstCurrentHop() {
        SourceFetchService service = service(false, false);
        String target = "https://93.184.216.34/moved/final";
        transport.redirect(PAGE, 307, "/moved/final")
                .on(target, 200, "text/plain; charset=utf-8", "moved");

        SourceFetchService.Fetched fetched = service.fetch(PAGE);

        assertEquals(target, fetched.finalUrl());
    }

    @Test
    void blocksRedirectToInternalAddressAndNeverContactsIt() {
        SourceFetchService service = service(false, true);
        transport.redirect(PAGE, 302, "https://10.0.0.1/admin");

        SourceFetchService.BlockedException e = assertThrows(SourceFetchService.BlockedException.class,
                () -> service.fetch(PAGE));

        assertEquals("internal_address", e.reason());
        assertTrue(e.detail().contains("10.0.0.1"), e.detail());
        assertTrue(transport.calls.stream().noneMatch(uri -> uri.toString().contains("10.0.0.1")),
                "不得向被拒绝的重定向目标发起请求");
    }

    @Test
    void blocksHttpsToHttpRedirectEvenWhenPlainHttpIsAllowed() {
        SourceFetchService service = service(true, false);
        transport.redirect(PAGE, 302, "http://93.184.216.34/plain");

        SourceFetchService.BlockedException e = assertThrows(SourceFetchService.BlockedException.class,
                () -> service.fetch(PAGE));

        assertEquals("redirect_scheme", e.reason());
        assertTrue(transport.calls.size() <= 2, "只允许请求初始地址（含可能的 robots）");
    }

    @Test
    void blocksRedirectLoopsBeyondLimit() {
        SourceFetchService service = service(false, false);
        String[] chain = {
                "https://93.184.216.40/0",
                "https://93.184.216.41/1",
                "https://93.184.216.42/2",
                "https://93.184.216.43/3",
                "https://93.184.216.44/4",
                "https://93.184.216.45/5"};
        for (int i = 0; i < chain.length - 1; i++) {
            transport.redirect(chain[i], 302, chain[i + 1]);
        }

        SourceFetchService.BlockedException e = assertThrows(SourceFetchService.BlockedException.class,
                () -> service.fetch(chain[0]));

        assertEquals("redirect_limit", e.reason());
        assertTrue(transport.calls.size() <= 5, "达到跳数上限后必须停止，实际请求 " + transport.calls.size());
        assertTrue(transport.calls.stream().noneMatch(uri -> uri.toString().equals(chain[5])),
                "第 6 个地址不得被请求");
    }

    @Test
    void rejectsOversizedBodyWithoutReadingItCompletely() {
        SourceFetchService service = service(false, false);
        byte[] oversized = new byte[1_048_576 + 4096];
        Arrays.fill(oversized, (byte) 'a');
        CountingInputStream counting = new CountingInputStream(new ByteArrayInputStream(oversized));
        transport.onStream(PAGE, 200, "text/plain", () -> counting);

        BusinessException e = assertThrows(BusinessException.class, () -> service.fetch(PAGE));

        assertEquals(ErrorCode.FILE_TOO_LARGE, e.code());
        assertTrue(counting.count.get() <= 1_048_576 + 1,
                "超过上限必须立即停止读取，实际读取 " + counting.count.get() + " 字节");
    }

    @Test
    void rejectsDeclaredContentLengthAboveLimitBeforeReading() {
        SourceFetchService service = service(false, false);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "text/html");
        headers.put("content-length", String.valueOf(50L * 1024 * 1024));
        transport.on(PAGE, 200, headers, () -> {
            throw new AssertionError("声明超限时不得读取正文");
        });

        BusinessException e = assertThrows(BusinessException.class, () -> service.fetch(PAGE));

        assertEquals(ErrorCode.FILE_TOO_LARGE, e.code());
    }

    @Test
    void rejectsUnsupportedContentType() {
        SourceFetchService service = service(false, false);
        transport.on(PAGE, 200, "application/pdf", "%PDF-1.7");

        BusinessException e = assertThrows(BusinessException.class, () -> service.fetch(PAGE));

        assertEquals(ErrorCode.UNSUPPORTED_MEDIA_TYPE, e.code());
    }

    @Test
    void robotsDisallowBlocksPageBeforeRequestingIt() {
        SourceFetchService service = service(false, true);
        String privatePage = "https://" + HOST + "/private/notice";
        transport.robots(HOST, 200, "User-agent: *\nDisallow: /private\n");

        SourceFetchService.BlockedException e = assertThrows(SourceFetchService.BlockedException.class,
                () -> service.fetch(privatePage));

        assertEquals("robots_denied", e.reason());
        assertTrue(transport.calls.stream().noneMatch(uri -> uri.toString().equals(privatePage)),
                "robots 拒绝时不得请求页面本身");
    }

    @Test
    void robotsSpecificGroupOverridesWildcard() {
        SourceFetchService service = service(false, true);
        transport.robots(HOST, 200, "User-agent: *\nDisallow: /\n\nUser-agent: ActionLoop\nAllow: /\n");
        transport.on(PAGE, 200, "text/plain; charset=utf-8", "允许抓取");

        SourceFetchService.Fetched fetched = service.fetch(PAGE);

        assertEquals("允许抓取", fetched.text());
    }

    @Test
    void robotsUnavailableBlocksConservatively() {
        SourceFetchService service = service(false, true);
        transport.robots(HOST, 503, "");

        SourceFetchService.BlockedException e = assertThrows(SourceFetchService.BlockedException.class,
                () -> service.fetch(PAGE));

        assertEquals("robots_denied", e.reason(), "robots 5xx 按 RFC 9309 视为全部禁止");
    }

    @Test
    void robotsDisallowCannotBeBypassedByPercentEncoding() {
        SourceFetchService service = service(false, true);
        String encoded = "https://" + HOST + "/%70rivate/notice";
        transport.robots(HOST, 200, "User-agent: *\nDisallow: /private\n");

        SourceFetchService.BlockedException e = assertThrows(SourceFetchService.BlockedException.class,
                () -> service.fetch(encoded));

        assertEquals("robots_denied", e.reason());
    }

    @Test
    void rejectsCompressedResponses() {
        SourceFetchService service = service(false, false);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "text/html");
        headers.put("content-encoding", "gzip");
        transport.on(PAGE, 200, headers, () -> new ByteArrayInputStream(new byte[]{1, 2, 3}));

        BusinessException e = assertThrows(BusinessException.class, () -> service.fetch(PAGE));

        assertEquals(ErrorCode.UNSUPPORTED_MEDIA_TYPE, e.code());
    }

    @Test
    void throttlesRepeatedFetchesToSameOrigin() {
        SourceFetchService service = service(false, false);
        transport.on(PAGE, 200, "text/plain; charset=utf-8", "ok");

        service.fetch(PAGE);

        BusinessException throttled = assertThrows(BusinessException.class, () -> service.fetch(PAGE));
        assertEquals(ErrorCode.RATE_LIMITED, throttled.code());

        clock[0] += Duration.ofSeconds(2).toNanos();
        service.fetch(PAGE);
    }

    // ---- 测试替身 ----

    private static final class FakeTransport implements SourceFetchService.HttpTransport {

        private final List<URI> calls = new ArrayList<>();
        private final Map<String, SourceFetchService.FetchResponse> responses = new LinkedHashMap<>();
        private final Map<String, SourceFetchService.FetchResponse> robots = new LinkedHashMap<>();

        FakeTransport on(String url, int status, String contentType, String body) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("content-type", contentType);
            return on(url, status, headers, () -> new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        FakeTransport on(String url, int status, Map<String, String> headers, Supplier<InputStream> body) {
            responses.put(url, new FakeResponse(status, headers, body));
            return this;
        }

        FakeTransport onStream(String url, int status, String contentType, Supplier<InputStream> body) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("content-type", contentType);
            return on(url, status, headers, body);
        }

        FakeTransport redirect(String url, int status, String location) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("location", location);
            return on(url, status, headers, () -> new ByteArrayInputStream(new byte[0]));
        }

        FakeTransport robots(String host, int status, String body) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("content-type", "text/plain");
            robots.put(host, new FakeResponse(status, headers,
                    () -> new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            return this;
        }

        @Override
        public SourceFetchService.FetchResponse send(URI uri, Duration timeout) throws Exception {
            calls.add(uri);
            if (uri.getPath() != null && uri.getPath().endsWith("/robots.txt")) {
                SourceFetchService.FetchResponse robotsResponse = robots.get(uri.getHost());
                return robotsResponse != null ? robotsResponse
                        : new FakeResponse(404, Map.of(), () -> new ByteArrayInputStream(new byte[0]));
            }
            SourceFetchService.FetchResponse response = responses.get(uri.toString());
            if (response == null) {
                throw new IOException("测试未预设该地址：" + uri);
            }
            return response;
        }
    }

    private static final class FakeResponse implements SourceFetchService.FetchResponse {

        private final int status;
        private final Map<String, String> headers;
        private final Supplier<InputStream> bodyFactory;

        FakeResponse(int status, Map<String, String> headers, Supplier<InputStream> bodyFactory) {
            this.status = status;
            this.headers = headers;
            this.bodyFactory = bodyFactory;
        }

        @Override
        public int status() {
            return status;
        }

        @Override
        public Optional<String> header(String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return Optional.of(entry.getValue());
                }
            }
            return Optional.empty();
        }

        @Override
        public InputStream body() {
            return bodyFactory.get();
        }

        @Override
        public void close() {
            // 假的响应无需释放
        }
    }

    private static final class CountingInputStream extends InputStream {

        private final InputStream delegate;
        private final AtomicLong count = new AtomicLong();

        CountingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count.incrementAndGet();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count.addAndGet(read);
            }
            return read;
        }
    }
}
