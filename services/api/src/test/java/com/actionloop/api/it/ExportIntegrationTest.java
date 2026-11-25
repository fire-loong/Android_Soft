package com.actionloop.api.it;

import com.actionloop.api.job.JobDispatcher;
import com.actionloop.api.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空间导出（指导书 11.7 / 13.2 / WP13）：密码二次认证、异步生成、短期下载、权限隔离。
 * 该用例专门防止导出 SQL 与迁移表结构漂移（曾因提醒/凭证/流程版本列名不匹配导致 500）。
 */
@TestPropertySource(properties = {
        "app.worker.jobs.enabled=true",
        "app.worker.jobs.interval-ms=600000"
})
class ExportIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JobDispatcher dispatcher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Map<String, Object> exportBody(String ws, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspaceId", ws);
        body.put("exportType", "WORKSPACE_JSON");
        body.put("password", password);
        return body;
    }

    @Test
    void exportRequiresRecentPasswordAndRunsAsynchronously() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("导出空间"));
        String title = unique("导出事项");
        createTask(token, ws, title);

        // 缺少密码：直接拒绝，不能创建任务
        Resp missing = req("POST", "/api/v1/exports", token,
                Map.of("workspaceId", ws, "exportType", "WORKSPACE_JSON"), null);
        assertEquals(400, missing.status, missing.raw);

        // 密码错误：401，且不产生导出任务
        Resp wrong = req("POST", "/api/v1/exports", token, exportBody(ws, "WrongPass1!"), null);
        assertEquals(401, wrong.status, "密码错误必须拒绝: " + wrong.raw);
        assertEquals("AUTH_INVALID", errCode(wrong), wrong.raw);
        Resp listedAfterFailure = req("GET", "/api/v1/exports", token, null, null);
        assertEquals(0, listedAfterFailure.json.path("data").size(), "密码错误不得创建导出任务");

        Resp created = req("POST", "/api/v1/exports", token, exportBody(ws, "Passw0rd!"), null);
        assertEquals(202, created.status, created.raw);
        assertEquals("PENDING", created.json.path("data").path("state").asText(), created.raw);
        String exportId = created.json.path("data").path("id").asText();
        assertTrue(!created.json.path("data").hasNonNull("downloadPath"), "未完成前不得给出下载路径");

        // worker 异步消费：最多派发 50 次以跳过其它测试遗留任务
        String state = "PENDING";
        for (int i = 0; i < 50 && !"READY".equals(state) && !"FAILED".equals(state); i++) {
            dispatcher.dispatchOne();
            Resp polled = req("GET", "/api/v1/exports/" + exportId, token, null, null);
            assertEquals(200, polled.status, polled.raw);
            state = polled.json.path("data").path("state").asText();
        }
        assertEquals("READY", state, "导出应被 worker 异步完成为 READY");

        Resp ready = req("GET", "/api/v1/exports/" + exportId, token, null, null);
        assertTrue(ready.json.path("data").path("sizeBytes").asLong() > 0, "导出内容不能为空");
        assertTrue(ready.json.path("data").path("expiresAt").asText().length() > 0, "必须给出短期有效期");
        assertTrue(ready.json.path("data").path("downloadPath").asText().contains(exportId), ready.raw);

        Resp download = req("GET", "/api/v1/exports/" + exportId + "/download", token, null, null);
        assertEquals(200, download.status, download.raw);
        assertTrue(download.raw.contains("actionloop-export-v1"), "导出必须带格式标记");
        assertTrue(download.raw.contains("\"workspace\""), "导出必须包含空间信息");
        assertTrue(download.raw.contains("\"tasks\""), "导出必须包含事项集合");
        assertTrue(download.raw.contains(title), "导出必须包含刚创建的事项");

        // 审计：请求与完成各写一条成功记录
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'export.create' AND result = 'SUCCESS' "
                        + "AND object_id = ?", Integer.class, exportId);
        assertTrue(audits != null && audits >= 2, "导出请求与完成都应写审计，实际=" + audits);
    }

    @Test
    void memberCannotExportAndStrangerCannotDownload() {
        Map<String, String> owner = register();
        String ownerToken = owner.get("access");
        String ws = createWorkspace(ownerToken, unique("导出权限空间"));

        Map<String, String> member = register();
        invite(ownerToken, ws, member.get("email"), "MEMBER");
        accept(member.get("access"), ws);
        Resp denied = req("POST", "/api/v1/exports", member.get("access"), exportBody(ws, "Passw0rd!"), null);
        assertEquals(403, denied.status, "普通成员不得导出整个空间: " + denied.raw);

        Resp created = req("POST", "/api/v1/exports", ownerToken, exportBody(ws, "Passw0rd!"), null);
        assertEquals(202, created.status, created.raw);
        String exportId = created.json.path("data").path("id").asText();
        for (int i = 0; i < 50; i++) {
            dispatcher.dispatchOne();
            Resp polled = req("GET", "/api/v1/exports/" + exportId, ownerToken, null, null);
            if ("READY".equals(polled.json.path("data").path("state").asText())) {
                break;
            }
        }

        // 其他用户（非空间成员）不得读取该导出
        Map<String, String> stranger = register();
        Resp hidden = req("GET", "/api/v1/exports/" + exportId + "/download",
                stranger.get("access"), null, null);
        assertEquals(404, hidden.status, "非成员不得获取导出: " + hidden.raw);
    }
}
