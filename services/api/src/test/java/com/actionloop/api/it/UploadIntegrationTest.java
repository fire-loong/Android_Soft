package com.actionloop.api.it;

import com.actionloop.api.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        return initUpload(token, ws, sha, size, "text/plain", "f.txt");
    }

    private String initUpload(String token, String ws, String sha, int size, String mime, String fileName) {
        Resp r = req("POST", "/api/v1/assets/uploads", token,
                Map.of("workspaceId", ws, "sha256", sha, "sizeBytes", size,
                        "mime", mime, "fileName", fileName), null);
        assertEquals(200, r.status, r.raw);
        return r.json.path("data").path("sessionId").asText();
    }

    /** 假扩展名：声明 PDF，实际是 PNG → 415 UNSUPPORTED_MEDIA_TYPE（指导书 B-04）。 */
    @Test
    void completeRejectsContentThatDoesNotMatchDeclaredType() throws Exception {
        var u = register();
        String ws = createWorkspace(u.get("access"), "up-fake-ext");
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 7, 7, 7};
        String sha = sha256(png);
        String sessionId = initUpload(u.get("access"), ws, sha, png.length, "application/pdf", "notice.pdf");

        assertEquals(200, putBytes("/api/v1/assets/uploads/" + sessionId + "/content", u.get("access"), png).status);
        Resp comp = req("POST", "/api/v1/assets/uploads/" + sessionId + "/complete", u.get("access"),
                Map.of("sha256", sha, "sizeBytes", png.length), null);

        assertEquals(415, comp.status, comp.raw);
        assertEquals("UNSUPPORTED_MEDIA_TYPE", errCode(comp));
        assertUploadFailedWithoutAsset(sessionId, sha);
    }

    /** 可执行文件伪装成文本 → 415，且不生成证据记录。 */
    @Test
    void completeRejectsExecutableContentEvenWhenDeclaredText() throws Exception {
        var u = register();
        String ws = createWorkspace(u.get("access"), "up-exe");
        byte[] exe = new byte[]{'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4};
        String sha = sha256(exe);
        String sessionId = initUpload(u.get("access"), ws, sha, exe.length, "text/plain", "readme.txt");

        assertEquals(200, putBytes("/api/v1/assets/uploads/" + sessionId + "/content", u.get("access"), exe).status);
        Resp comp = req("POST", "/api/v1/assets/uploads/" + sessionId + "/complete", u.get("access"),
                Map.of("sha256", sha, "sizeBytes", exe.length), null);

        assertEquals(415, comp.status, comp.raw);
        assertEquals("UNSUPPORTED_MEDIA_TYPE", errCode(comp));
        assertUploadFailedWithoutAsset(sessionId, sha);
    }

    /** 含宏工程 vbaProject.bin 的 docx 容器 → 415，不执行、不落证据。 */
    @Test
    void completeRejectsMacroEnabledOfficeContainer() throws Exception {
        var u = register();
        String ws = createWorkspace(u.get("access"), "up-macro");
        byte[] docm = zip(
                "word/document.xml", "<w:document/>",
                "word/vbaProject.bin", "macro-bytes");
        String sha = sha256(docm);
        String docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        String sessionId = initUpload(u.get("access"), ws, sha, docm.length, docxMime, "material.docm");

        assertEquals(200, putBytes("/api/v1/assets/uploads/" + sessionId + "/content", u.get("access"), docm).status);
        Resp comp = req("POST", "/api/v1/assets/uploads/" + sessionId + "/complete", u.get("access"),
                Map.of("sha256", sha, "sizeBytes", docm.length), null);

        assertEquals(415, comp.status, comp.raw);
        assertEquals("UNSUPPORTED_MEDIA_TYPE", errCode(comp));
        assertUploadFailedWithoutAsset(sessionId, sha);
    }

    /** 失败后会话必须落为 FAILED 且不留证据：验证异常未被事务回滚吞掉（审查 P1）。 */
    private void assertUploadFailedWithoutAsset(String sessionId, String sha) {
        String state = jdbcTemplate.queryForObject(
                "SELECT state FROM upload_sessions WHERE id = ?::uuid", String.class, sessionId);
        assertEquals("FAILED", state, "失败上传会话状态必须持久化为 FAILED");
        Integer assets = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM evidence_assets WHERE sha256 = ?", Integer.class, sha);
        assertEquals(0, assets, "被拒绝的上传不得生成证据记录");
    }

    private static String sha256(byte[] content) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(content);
        return java.util.HexFormat.of().formatHex(digest);
    }

    /** 用 STORED + 显式大小写 zip，避免流式 data descriptor（服务端为保证不解压，拒绝此类压缩包）。 */
    private static byte[] zip(String... nameAndBody) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(out)) {
            for (int i = 0; i + 1 < nameAndBody.length; i += 2) {
                byte[] body = nameAndBody[i + 1].getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var entry = new java.util.zip.ZipEntry(nameAndBody[i]);
                entry.setMethod(java.util.zip.ZipEntry.STORED);
                entry.setSize(body.length);
                entry.setCompressedSize(body.length);
                var crc = new java.util.zip.CRC32();
                crc.update(body);
                entry.setCrc(crc.getValue());
                zip.putNextEntry(entry);
                zip.write(body);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
