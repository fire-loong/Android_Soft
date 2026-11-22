package com.actionloop.api.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 集成测试基类：启动随机端口的完整 Spring 上下文，连向 TestInfraSettings 指定的真实 PG/Redis。
 * 无基础设施时由 {@link EnabledIfTestInfra} 跳过，不启动上下文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfTestInfra
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected ObjectMapper json;

    @BeforeEach
    void useApacheHttpClient() {
        // JDK HttpURLConnection 不支持 PATCH 且对带 body 的 4xx 抛 HttpRetryException；改用 Apache HttpClient5。
        http.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    @DynamicPropertySource
    static void registerInfra(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TestInfraSettings::jdbcUrl);
        r.add("spring.datasource.username", TestInfraSettings::pgUser);
        r.add("spring.datasource.password", TestInfraSettings::pgPassword);
        r.add("spring.data.redis.host", TestInfraSettings::redisHost);
        r.add("spring.data.redis.port", TestInfraSettings::redisPort);
        r.add("spring.data.redis.password", TestInfraSettings::redisPassword);
    }

    /** 响应：状态码 + 解析后的 JSON + 原始文本。字段公开以便子类断言直接访问。 */
    protected static final class Resp {
        public final int status;
        public final JsonNode json;
        public final String raw;

        Resp(int status, JsonNode json, String raw) {
            this.status = status;
            this.json = json;
            this.raw = raw;
        }
    }

    protected String unique(String prefix) {
        return prefix + "_" + System.nanoTime() + "_" + (int) (Math.random() * 100000);
    }

    protected Resp req(String method, String path, String token, Object body, String idemKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        if (idemKey != null) {
            h.set("Idempotency-Key", idemKey);
        }
        String payload = null;
        if (body != null) {
            try {
                payload = (body instanceof String s) ? s : json.writeValueAsString(body);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        HttpEntity<String> entity = new HttpEntity<>(payload, h);
        ResponseEntity<String> resp = http.exchange(path, HttpMethod.valueOf(method), entity, String.class);
        JsonNode node = null;
        if (resp.hasBody()) {
            try {
                node = json.readTree(resp.getBody());
            } catch (Exception ignored) {
                // 非 JSON 响应（如下载字节流）保留 raw
            }
        }
        return new Resp(resp.getStatusCode().value(), node, resp.getBody());
    }

    protected Resp putBytes(String path, String token, byte[] bytes) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        HttpEntity<byte[]> entity = new HttpEntity<>(bytes, h);
        ResponseEntity<String> resp = http.exchange(path, HttpMethod.PUT, entity, String.class);
        JsonNode node = null;
        if (resp.hasBody()) {
            try {
                node = json.readTree(resp.getBody());
            } catch (Exception ignored) {
            }
        }
        return new Resp(resp.getStatusCode().value(), node, resp.getBody());
    }

    protected String errCode(Resp r) {
        return r.json == null ? "" : r.json.path("error").path("code").asText("");
    }

    // ---- 常用夹具 ----

    protected Map<String, String> register() {
        String u = unique("u");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", u + "@t.test");
        body.put("password", "Passw0rd!");
        body.put("username", u);
        body.put("deviceName", "w");
        body.put("devicePlatform", "WEB");
        Resp r = req("POST", "/api/v1/auth/register", null, body, null);
        if (r.status != 200) {
            throw new IllegalStateException("register failed: " + r.status + " " + r.raw);
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("access", r.json.path("data").path("accessToken").asText());
        m.put("refresh", r.json.path("data").path("refreshToken").asText());
        m.put("userId", r.json.path("data").path("user").path("id").asText());
        m.put("email", u + "@t.test");
        m.put("username", u);
        return m;
    }

    protected String createWorkspace(String token, String name) {
        Resp r = req("POST", "/api/v1/workspaces", token, Map.of("name", name), null);
        if (r.status != 200) {
            throw new IllegalStateException("create workspace failed: " + r.status + " " + r.raw);
        }
        return r.json.path("data").path("id").asText();
    }

    protected void invite(String ownerToken, String wsId, String email, String role) {
        Resp r = req("POST", "/api/v1/workspaces/" + wsId + "/members", ownerToken,
                Map.of("email", email, "role", role), null);
        if (r.status != 200) {
            throw new IllegalStateException("invite failed: " + r.status + " " + r.raw);
        }
    }

    protected void accept(String memberToken, String wsId) {
        Resp r = req("POST", "/api/v1/workspaces/" + wsId + "/invitations/accept", memberToken, null, null);
        if (r.status != 200) {
            throw new IllegalStateException("accept failed: " + r.status + " " + r.raw);
        }
    }

    protected String createTask(String token, String wsId, String title) {
        Resp r = req("POST", "/api/v1/tasks", token, Map.of("workspaceId", wsId, "title", title), null);
        if (r.status != 200) {
            throw new IllegalStateException("create task failed: " + r.status + " " + r.raw);
        }
        return r.json.path("data").path("id").asText();
    }

    protected static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
