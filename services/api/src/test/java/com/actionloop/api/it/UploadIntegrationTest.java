package com.actionloop.api.it;

import com.actionloop.api.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 文件上传完整性 + 下载 ACL 集成：
 * - 声明哈希与实际内容不符 → complete 拒绝（fail-closed）
 * - 正常上传后下载链接可获取内容；篡改签名中的 userId → 401
 */
class UploadIntegrationTest extends AbstractIntegrationTest {

    /** "hello world" 的 sha256 */
    private static final String REAL_SHA =
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";
    /** 一个明显错误的声明哈希 */
    private static final String FAKE_SHA =
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    private static final byte[] CONTENT = utf8("hello world");

    @Test
    void completeRejectsWhenDeclaredHashMismatches() {
        var u = register();
        String ws = createWorkspace(u.get("access"), "up-bad");
        String sessionId = initUpload(u.get("access"), ws, FAKE_SHA, CONTENT.length);

        Resp put = putBytes("/api/v1/assets/uploads/" + sessionId + "/content", u.get("access"), CONTENT);
        assertEquals(200, put.status, put.raw);

        Resp comp = req("POST", "/api/v1/assets/uploads/" + sessionId + "/complete", u.get("access"),
                Map.of("sha256", FAKE_SHA, "sizeBytes", CONTENT.length), null);
        assertEquals(422, comp.status, "mismatched hash must be rejected: " + comp.raw);
        assertEquals("ASSET_INTEGRITY_MISMATCH", errCode(comp));
    }

    @Test
    void validUploadDownloadsAndTamperedTokenIsRejected() {
        var u = register();
        String ws = createWorkspace(u.get("access"), "up-ok");
        String sessionId = initUpload(u.get("access"), ws, REAL_SHA, CONTENT.length);

        Resp put = putBytes("/api/v1/assets/uploads/" + sessionId + "/content", u.get("access"), CONTENT);
        assertEquals(200, put.status, put.raw);

        Resp comp = req("POST", "/api/v1/assets/uploads/" + sessionId + "/complete", u.get("access"),
                Map.of("sha256", REAL_SHA, "sizeBytes", CONTENT.length), null);
        assertEquals(200, comp.status, comp.raw);
        String assetId = comp.json.path("data").path("id").asText();

        Resp du = req("GET", "/api/v1/assets/" + assetId + "/download-url?workspaceId=" + ws,
                u.get("access"), null, null);
        assertEquals(200, du.status, du.raw);
        String url = du.json.path("data").asText();

        // 合法下载（无 Authorization 头，凭签名 URL）
        ResponseEntity<byte[]> dl = http.getForEntity(URI.create(url), byte[].class);
        assertEquals(200, dl.getStatusCode().value(), "download should succeed");
        assertArrayEquals(CONTENT, dl.getBody(), "downloaded content must match uploaded");

        // 篡改签名中的 userId → 签名校验失败
        String bad = url.replaceFirst("userId=[0-9a-fA-F-]+",
                "userId=22222222-2222-2222-2222-222222222222");
        ResponseEntity<byte[]> tampered = http.getForEntity(URI.create(bad), byte[].class);
        assertEquals(401, tampered.getStatusCode().value(), "tampered download token must be rejected");
    }

    private String initUpload(String token, String ws, String sha, int size) {
        Resp r = req("POST", "/api/v1/assets/uploads", token,
                Map.of("workspaceId", ws, "sha256", sha, "sizeBytes", size,
                        "mime", "text/plain", "fileName", "f.txt"), null);
        assertEquals(200, r.status, r.raw);
        return r.json.path("data").path("sessionId").asText();
    }
}
