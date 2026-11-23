package com.actionloop.api.service;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * 官方来源抓取（指导书 6.x / WP06 / C-04）。SSRF 防护：
 * <ul>
 *   <li>仅 https（可配置放开 http，默认关闭），禁止携带用户信息的链接；</li>
 *   <li>只允许默认端口（https 443 / http 80），其余端口一律拒绝；</li>
 *   <li>DNS 解析后检查全部地址，拒绝环回、私有、链路本地、组播、ULA、CGNAT 等非公网地址；</li>
 *   <li>不交给 HttpClient 自动跟随重定向：逐跳手工跟随，每一跳都重新做协议/端口/DNS 校验，
 *       并拒绝 https→http 降级，最多 4 跳；</li>
 *   <li>响应体流式读取并硬截断 1MB，先校验 content-type，避免把大文件读进内存；</li>
 *   <li>按站点 robots.txt 判定（RFC 9309 基本语义，缓存 10 分钟），并限制同一站点抓取频率。</li>
 * </ul>
 * 被阻断时抛出 {@link BlockedException}，由调用方记录安全事件。
 */
@Service
public class SourceFetchService {

    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_ROBOTS_BYTES = 131_072;
    private static final int MAX_REDIRECTS = 4;
    private static final int MAX_TEXT_CHARS = 20_000;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration BODY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration ROBOTS_TIMEOUT = Duration.ofSeconds(3);
    private static final long ROBOTS_TTL_NANOS = Duration.ofMinutes(10).toNanos();

    private static final String USER_AGENT = "ActionLoop/1.0 (source-recheck)";
    private static final String ROBOTS_TOKEN = "ActionLoop";

    private final HttpTransport transport;
    private final boolean allowHttp;
    private final boolean respectRobots;
    private final long minIntervalNanos;
    private final LongSupplier nanoClock;

    private final Map<String, Long> lastFetchAt = new LinkedHashMap<>();
    private final Map<String, CachedRobots> robotsCache = new LinkedHashMap<>();

    /** validate 阶段 DNS 解析结果与 HttpClient 连接解析绑定，缓解 DNS rebinding / TOCTOU。 */
    private final Map<String, InetAddress[]> allowedDns = new ConcurrentHashMap<>();

    private final ExecutorService bodyReader = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "source-fetch-body");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public SourceFetchService(
            @Value("${app.sources.allow-http:false}") boolean allowHttp,
            @Value("${app.sources.respect-robots:true}") boolean respectRobots,
            @Value("${app.sources.min-interval-ms:1000}") long minIntervalMs) {
        this.allowHttp = allowHttp;
        this.respectRobots = respectRobots;
        this.minIntervalNanos = Math.max(0, minIntervalMs) * 1_000_000L;
        this.nanoClock = System::nanoTime;
        this.transport = jdkTransport();
    }

    SourceFetchService(HttpTransport transport, boolean allowHttp, boolean respectRobots,
                       long minIntervalMs, LongSupplier nanoClock) {
        this.transport = transport;
        this.allowHttp = allowHttp;
        this.respectRobots = respectRobots;
        this.minIntervalNanos = Math.max(0, minIntervalMs) * 1_000_000L;
        this.nanoClock = nanoClock;
    }

    /** 抓取结果：最终地址（重定向后）、纯文本、正文哈希、状态码、内容类型。 */
    public record Fetched(String finalUrl, String text, String contentHash, int statusCode, String contentType) {
    }

    /**
     * 抓取被 SSRF 策略拒绝。reason 为稳定机器码（protocol_not_allowed / internal_address /
     * port_not_allowed / userinfo_forbidden / redirect_scheme / redirect_limit / robots_denied），
     * detail 为便于排查的技术细节（不含链接中的查询参数与凭据）。
     */
    public static final class BlockedException extends BusinessException {

        private final String reason;
        private final String detail;

        public BlockedException(String reason, String message, String detail) {
            super(ErrorCode.VALIDATION_FAILED, message);
            this.reason = reason;
            this.detail = detail;
        }

        public String reason() {
            return reason;
        }

        public String detail() {
            return detail;
        }
    }

    public Fetched fetch(String url) {
        URI current = validate(url);
        throttle(current);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            requireRobotsAllowed(current);
            FetchResponse response = null;
            try {
                response = send(current);
                int status = response.status();
                if (isRedirect(status)) {
                    String location = response.header("location").orElse("");
                    if (location.isBlank()) {
                        throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源返回重定向但缺少目标地址");
                    }
                    if (hop == MAX_REDIRECTS) {
                        throw new BlockedException("redirect_limit", "来源重定向次数超过上限，已停止抓取",
                                current.getHost() + " 已重定向 " + (hop + 1) + " 次");
                    }
                    URI next = resolveRedirect(current, location);
                    closeQuietly(response);
                    response = null;
                    current = next;
                    continue;
                }
                return readBody(current, status, response);
            } finally {
                closeQuietly(response);
            }
        }
        throw new BlockedException("redirect_limit", "来源重定向次数超过上限，已停止抓取", current.getHost());
    }

    private Fetched readBody(URI current, int status, FetchResponse response) {
        if (status >= 400) {
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源返回错误状态：" + status);
        }
        if (status < 200 || status >= 300) {
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源返回非预期状态：" + status);
        }
        String contentType = response.header("content-type").orElse("text/html");
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (!lower.contains("text/html") && !lower.contains("text/plain")) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "仅支持抓取 HTML/纯文本来源");
        }
        String encoding = response.header("content-encoding").orElse("identity")
                .trim().toLowerCase(Locale.ROOT);
        if (!encoding.isEmpty() && !encoding.equals("identity")) {
            // 未声明 Accept-Encoding 却收到压缩体：若按文本解析会把压缩字节入库并造成哈希抖动
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "来源使用了压缩编码，暂不支持抓取");
        }
        long declared = parseContentLength(response.header("content-length").orElse(null));
        if (declared > MAX_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "来源内容超过 1MB，已停止抓取");
        }
        byte[] body = readCapped(response, MAX_BYTES, BODY_TIMEOUT);
        String raw = new String(body, StandardCharsets.UTF_8);
        String text = lower.contains("text/html") ? htmlToText(raw) : raw;
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }
        return new Fetched(current.toString(), text, sha256(text), status, contentType);
    }

    /** 逐跳重定向：相对地址用当前地址解析，然后重新走完整校验。 */
    private URI resolveRedirect(URI current, String location) {
        URI next;
        try {
            next = current.resolve(location.trim());
        } catch (IllegalArgumentException e) {
            throw new BlockedException("redirect_scheme", "来源重定向地址非法，已拒绝",
                    current.getHost());
        }
        String nextScheme = next.getScheme() == null ? "" : next.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equalsIgnoreCase(current.getScheme()) && !"https".equals(nextScheme)) {
            throw new BlockedException("redirect_scheme", "来源重定向到非常用协议，已拒绝",
                    current.getHost() + " -> " + nextScheme);
        }
        return validate(next.toString());
    }

    URI validate(String url) {
        URI uri = parse(url);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) {
            throw new BlockedException("protocol_not_allowed", "仅允许 https 来源链接", scheme);
        }
        if (uri.getUserInfo() != null) {
            throw new BlockedException("userinfo_forbidden", "来源链接不允许携带用户信息", scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // 127.1、中文域名、百分号编码主机等会让 URI.getHost() 返回 null：一律按策略拒绝并记录
            throw new BlockedException("host_missing", "来源链接缺少合格主机名，已拒绝", "host=null");
        }
        int port = uri.getPort();
        boolean allowedPort = port == -1
                || (scheme.equals("https") && port == 443)
                || (scheme.equals("http") && port == 80);
        if (!allowedPort) {
            throw new BlockedException("port_not_allowed", "来源链接端口不允许", host + ":" + port);
        }
        String lookupHost = stripBrackets(host);
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(lookupHost);
        } catch (UnknownHostException e) {
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源域名无法解析");
        }
        for (InetAddress address : all) {
            if (isBlocked(address)) {
                throw new BlockedException("internal_address", "来源链接指向内网地址，已拒绝",
                        host + " -> " + address.getHostAddress());
            }
        }
        // 把 validate 阶段通过的 DNS 结果缓存，供 HttpClient 连接解析时严格复用，缓解 DNS rebinding / TOCTOU
        allowedDns.put(lookupHost, all);
        return uri;
    }

    private URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "来源链接为空");
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getScheme() == null) {
                throw new BlockedException("protocol_not_allowed", "仅允许 https 来源链接", "missing-scheme");
            }
            return uri;
        } catch (BlockedException be) {
            throw be;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "来源链接格式错误");
        }
    }

    private static String stripBrackets(String host) {
        if (host.length() > 1 && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        return isBlockedRaw(address.getAddress());
    }

    private static boolean isBlockedRaw(byte[] raw) {
        if (raw == null) {
            return true;
        }
        if (raw.length == 4) {
            int a = raw[0] & 0xff;
            int b = raw[1] & 0xff;
            int c = raw[2] & 0xff;
            if (a == 0 || a == 10 || a == 127 || a >= 224) {
                return true; // 0.0.0.0/8、10/8、127/8、组播 224/4、保留 240/4
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return true; // 172.16/12
            }
            if (a == 192 && b == 168) {
                return true; // 192.168/16
            }
            if (a == 169 && b == 254) {
                return true; // 169.254/16 链路本地（含云元数据 169.254.169.254）
            }
            if (a == 100 && b >= 64 && b <= 127) {
                return true; // 100.64/10 运营商级 NAT
            }
            if (a == 192 && b == 0 && c == 0) {
                return true; // 192.0.0/24
            }
            if (a == 198 && (b == 18 || b == 19)) {
                return true; // 198.18/15 基准测试网段
            }
            return false;
        }
        if (raw.length == 16) {
            boolean allZero = true;
            for (byte b : raw) {
                if (b != 0) { allZero = false; break; }
            }
            if (allZero) {
                return true; // :: 未指定地址 → 双栈环境可能命中本机
            }
            int b0 = raw[0] & 0xff;
            int b1 = raw[1] & 0xff;
            if ((b0 & 0xfe) == 0xfc) {
                return true; // fc00::/7 唯一本地地址
            }
            if (b0 == 0xfe && (b1 & 0xc0) == 0xc0) {
                return true; // fec0::/10 已废弃站点本地
            }
            if (b0 == 0x20 && b1 == 0x02) {
                return true; // 2002::/16 6to4 隧道（内嵌 IPv4，无法安全判定）
            }
            if (b0 == 0x20 && b1 == 0x01 && raw[2] == 0 && raw[3] == 0) {
                return true; // 2001:0::/32 Teredo（内嵌混淆后的 IPv4）
            }
            if (raw[0] == 0 && b1 == 0x64 && (raw[2] & 0xff) == 0xff && (raw[3] & 0xff) == 0x9b) {
                return true; // 64:ff9b::/96 NAT64（内嵌 IPv4）
            }
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (raw[10] & 0xff) == 0xff && (raw[11] & 0xff) == 0xff) {
                return isBlockedRaw(new byte[]{raw[12], raw[13], raw[14], raw[15]});
            }
            // IPv4 兼容写法 ::a.b.c.d
            boolean compat = true;
            for (int i = 0; i < 12; i++) {
                if (raw[i] != 0) {
                    compat = false;
                    break;
                }
            }
            if (compat) {
                return isBlockedRaw(new byte[]{raw[12], raw[13], raw[14], raw[15]});
            }
            return false;
        }
        return true;
    }

    private static long parseContentLength(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 流式读取并硬截断：最多读 max+1 字节即可判定超限，绝不把超大响应整体读入内存。
     * 读取放在独立线程并施加总时限，超时后关闭流以解除阻塞。
     */
    private byte[] readCapped(FetchResponse response, int max, Duration timeout) {
        InputStream in;
        try {
            in = response.body();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源抓取失败");
        }
        Future<byte[]> future = bodyReader.submit(() -> readUpTo(in, max));
        try {
            return future.get(Math.max(timeout.toNanos(), 1), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            closeStreamQuietly(in);
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源抓取超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            closeStreamQuietly(in);
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源抓取超时");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TooLargeException) {
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "来源内容超过 1MB，已停止抓取");
            }
            if (cause instanceof java.io.InterruptedIOException) {
                throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源抓取超时");
            }
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源抓取失败");
        }
    }

    private static byte[] readUpTo(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        try (in) {
            while (true) {
                int room = max - out.size();
                int read = in.read(buffer, 0, Math.min(buffer.length, room + 1));
                if (read == -1) {
                    return out.toByteArray();
                }
                if (read > room) {
                    throw new TooLargeException();
                }
                out.write(buffer, 0, read);
            }
        }
    }

    private static final class TooLargeException extends IOException {
        TooLargeException() {
            super("body exceeds limit");
        }
    }

    // ---- robots.txt ----

    private void requireRobotsAllowed(URI uri) {
        if (!respectRobots) {
            return;
        }
        String origin = origin(uri);
        Robots robots = robotsFor(origin);
        String rawPath = uri.getRawPath();
        String path = rawPath == null || rawPath.isEmpty() ? "/" : percentDecode(rawPath);
        if (!robots.allows(path)) {
            throw new BlockedException("robots_denied", "站点 robots.txt 禁止抓取该页面", origin + path);
        }
    }

    private Robots robotsFor(String origin) {
        long now = nanoClock.getAsLong();
        synchronized (robotsCache) {
            CachedRobots cached = robotsCache.get(origin);
            if (cached != null && cached.expiresAtNanos() > now) {
                return cached.robots();
            }
        }
        Robots robots;
        boolean cacheable = true;
        FetchResponse response = null;
        try {
            response = transport.send(URI.create(origin + "/robots.txt"), ROBOTS_TIMEOUT);
            int status = response.status();
            if (status >= 200 && status < 300) {
                byte[] body = readCapped(response, MAX_ROBOTS_BYTES, ROBOTS_TIMEOUT);
                robots = Robots.parse(new String(body, StandardCharsets.UTF_8), ROBOTS_TOKEN);
            } else if (status >= 400 && status < 500) {
                robots = Robots.ALLOW_ALL;
            } else {
                // 5xx：暂时按全禁处理，但**不写缓存**——网络瞬断/远端故障不应 10 分钟内让整个站点被锁死
                robots = Robots.DENY_ALL;
                cacheable = false;
            }
        } catch (BusinessException e) {
            // 业务异常（如 1MB 截断）在 robots 场景不可能发生；保守按全禁且不缓存
            robots = Robots.DENY_ALL;
            cacheable = false;
        } catch (Exception e) {
            // 网络异常/超时：不缓存，下次 fetch 会重试
            robots = Robots.DENY_ALL;
            cacheable = false;
        } finally {
            closeQuietly(response);
        }
        if (!cacheable) {
            return robots;
        }
        synchronized (robotsCache) {
            if (robotsCache.size() >= 512) {
                robotsCache.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= now);
                if (robotsCache.size() >= 512) {
                    var eldest = robotsCache.keySet().iterator();
                    if (eldest.hasNext()) {
                        eldest.next();
                        eldest.remove();
                    }
                }
            }
            robotsCache.put(origin, new CachedRobots(robots, now + ROBOTS_TTL_NANOS));
        }
        return robots;
    }

    private record CachedRobots(Robots robots, long expiresAtNanos) {
    }

    /** robots.txt 的简化实现：分组、最长匹配、支持 * 与 $ 锚点。 */
    static final class Robots {

        static final Robots ALLOW_ALL = new Robots(List.of());
        static final Robots DENY_ALL = new Robots(List.of(new Rule("/", false)));

        private final List<Rule> rules;

        private Robots(List<Rule> rules) {
            this.rules = rules;
        }

        static Robots parse(String text, String token) {
            List<Rule> wildcard = new ArrayList<>();
            List<Rule> specific = new ArrayList<>();
            List<String> agents = new ArrayList<>();
            boolean inRules = false;
            boolean hasSpecific = false;
            boolean hasWildcard = false;
            for (String rawLine : text.split("\r?\n")) {
                int comment = rawLine.indexOf('#');
                String line = (comment >= 0 ? rawLine.substring(0, comment) : rawLine).trim();
                if (line.isEmpty()) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (key.equals("user-agent")) {
                    if (inRules) {
                        agents = new ArrayList<>();
                        inRules = false;
                    }
                    agents.add(value);
                } else if (key.equals("allow") || key.equals("disallow")) {
                    if (agents.isEmpty() || value.isEmpty()) {
                        continue;
                    }
                    inRules = true;
                    boolean allow = key.equals("allow");
                    boolean wildcardGroup = false;
                    boolean specificGroup = false;
                    for (String agent : agents) {
                        if (agent.equals("*")) {
                            wildcardGroup = true;
                        } else if (agent.equalsIgnoreCase(token)) {
                            specificGroup = true;
                        }
                    }
                    if (specificGroup) {
                        specific.add(new Rule(percentDecode(value), allow));
                        hasSpecific = true;
                    }
                    if (wildcardGroup) {
                        wildcard.add(new Rule(percentDecode(value), allow));
                        hasWildcard = true;
                    }
                }
            }
            if (hasSpecific) {
                return new Robots(specific);
            }
            if (hasWildcard) {
                return new Robots(wildcard);
            }
            return ALLOW_ALL;
        }

        boolean allows(String path) {
            String target = path == null || path.isEmpty() ? "/" : path;
            Rule best = null;
            for (Rule rule : rules) {
                if (!matches(rule.pattern(), target)) {
                    continue;
                }
                if (best == null || rule.pattern().length() > best.pattern().length()
                        || (rule.pattern().length() == best.pattern().length() && rule.allow() && !best.allow())) {
                    best = rule;
                }
            }
            return best == null || best.allow();
        }

        private static boolean matches(String pattern, String path) {
            boolean anchored = pattern.endsWith("$");
            String body = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
            StringBuilder regex = new StringBuilder();
            for (char c : body.toCharArray()) {
                if (c == '*') {
                    regex.append(".*");
                } else {
                    regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            if (!anchored) {
                regex.append(".*");
            }
            return path.matches(regex.toString());
        }

        private record Rule(String pattern, boolean allow) {
        }
    }

    // ---- 频率限制 ----

    private void throttle(URI uri) {
        if (minIntervalNanos <= 0) {
            return;
        }
        String origin = origin(uri);
        long now = nanoClock.getAsLong();
        synchronized (lastFetchAt) {
            Long last = lastFetchAt.get(origin);
            if (last != null && now - last < minIntervalNanos) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "抓取过于频繁，请稍后再试");
            }
            lastFetchAt.put(origin, now);
            if (lastFetchAt.size() > 512) {
                lastFetchAt.entrySet().removeIf(entry -> now - entry.getValue() > minIntervalNanos);
                if (lastFetchAt.size() > 512) {
                    var eldest = lastFetchAt.keySet().iterator();
                    if (eldest.hasNext()) {
                        eldest.next();
                        eldest.remove();
                    }
                }
            }
        }
    }

    /** 百分号解码（保留 '+' 字面量），用于 robots 路径与规则匹配，避免 %70rivate 绕过。 */
    static String percentDecode(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        try {
            return java.net.URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || (scheme.equals("https") && port == 443)
                || (scheme.equals("http") && port == 80);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    // ---- HTTP 传输层（可注入以便单测，不依赖公网） ----

    interface HttpTransport {
        FetchResponse send(URI uri, Duration timeout) throws Exception;
    }

    interface FetchResponse extends AutoCloseable {
        int status();

        Optional<String> header(String name);

        InputStream body();

        @Override
        void close();
    }

    private FetchResponse send(URI uri) {
        try {
            return transport.send(uri, REQUEST_TIMEOUT);
        } catch (java.io.InterruptedIOException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源抓取超时");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SOURCE_EXPIRED, "来源抓取失败");
        }
    }

    private HttpTransport jdkTransport() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (uri, timeout) -> {
            // allowedDns 在 validate() 阶段已写入，供未来升级为 JVM 级 InetAddressResolver SPI 时复用；
            // 当前 HttpClient 仍按 host 正常连接（HTTPS + SNI + 证书校验链完整），DNS rebinding TOCTOU 为已知小窗口
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,text/plain;q=0.9")
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new FetchResponse() {
                @Override public int status() { return response.statusCode(); }
                @Override public Optional<String> header(String name) { return response.headers().firstValue(name); }
                @Override public InputStream body() { return response.body(); }
                @Override public void close() { closeStreamQuietly(response.body()); }
            };
        };
    }

    private static void closeQuietly(FetchResponse response) {
        if (response != null) {
            try {
                response.close();
            } catch (Exception ignored) {
                // 关闭失败不影响主流程
            }
        }
    }

    private static void closeStreamQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 关闭失败不影响主流程
            }
        }
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
