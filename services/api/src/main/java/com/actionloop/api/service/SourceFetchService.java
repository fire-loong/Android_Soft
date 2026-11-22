package com.actionloop.api.service;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 官方来源抓取（指导书 6.x / WP06）。SSRF 防护：
 * 仅 https、禁止重定向、DNS 解析后拒绝私有/环回/链路本地/组播地址、5s 连接、8s 请求、1MB 上限。
 */
@Service
public class SourceFetchService {

    private static final int MAX_BYTES = 1_048_576;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Value("${app.sources.allow-http:false}")
    private boolean allowHttp;

    public record Fetched(String finalUrl, String text, String contentHash, int statusCode, String contentType) {
    }

    public Fetched fetch(String url) {
        URI uri = validate(url);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "ActionLoop/1.0 (source-recheck)")
                    .header("Accept", "text/html,text/plain;q=0.9")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源返回错误状态：" + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length > MAX_BYTES) {
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "来源内容超过 1MB，已停止抓取");
            }
            String contentType = response.headers().firstValue("content-type").orElse("text/html");
            String lower = contentType.toLowerCase(Locale.ROOT);
            if (!lower.contains("text/html") && !lower.contains("text/plain")) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "仅支持抓取 HTML/纯文本来源");
            }
            String raw = new String(body, StandardCharsets.UTF_8);
            String text = lower.contains("text/html") ? htmlToText(raw) : raw;
            if (text.length() > 20000) {
                text = text.substring(0, 20000);
            }
            return new Fetched(uri.toString(), text, sha256(text), response.statusCode(), contentType);
        } catch (BusinessException be) {
            throw be;
        } catch (java.io.InterruptedIOException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源抓取超时");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源抓取失败");
        }
    }

    URI validate(String url) {
        if (url == null || url.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "来源链接为空");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "来源链接格式错误");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || (allowHttp && scheme.equalsIgnoreCase("http")))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "仅允许 https 来源链接");
        }
        if (uri.getUserInfo() != null || uri.getHost() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "来源链接不允许携带用户信息");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "来源链接端口不允许");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isBlocked(address)) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "来源链接指向内网地址，已拒绝");
                }
            }
        } catch (BusinessException be) {
            throw be;
        } catch (java.net.UnknownHostException e) {
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源域名无法解析");
        }
        return uri;
    }

    private static boolean isBlocked(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()
                || isUniqueLocal(address.getAddress());
    }

    private static boolean isUniqueLocal(byte[] raw) {
        return raw != null && raw.length == 16 && (raw[0] & 0xfe) == 0xfc;
    }

    static String htmlToText(String html) {
        String noScript = html.replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</\\1>", " ");
        String noTags = noScript.replaceAll("(?s)<[^>]+>", " ");
        String decoded = noTags
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return decoded.replaceAll("\\s+", " ").trim();
    }

    static String sha256(String text) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
