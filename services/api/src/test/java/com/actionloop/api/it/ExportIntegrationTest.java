package com.actionloop.api.it;

import com.actionloop.api.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空间导出（指导书 11.7 / WP13）：真实数据库快照、私有下载、权限隔离。
 * 该用例专门防止导出 SQL 与迁移表结构漂移（曾因提醒/凭证/流程版本列名不匹配导致 500）。
 */
class ExportIntegrationTest extends AbstractIntegrationTest {

    @Test
    void exportProducesDownloadablePrivateBundle() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("导出空间"));

        Resp created = req("POST", "/api/v1/exports", token,
                Map.of("workspaceId", ws, "exportType", "WORKSPACE_JSON"), null);
        assertEquals(200, created.status, created.raw);
        assertEquals("READY", created.json.path("data").path("state").asText(), created.raw);
        assertTrue(created.json.path("data").path("sizeBytes").asLong() > 0, "导出内容不能为空");
        String exportId = created.json.path("data").path("id").asText();

        Resp download = req("GET", "/api/v1/exports/" + exportId + "/download", token, null, null);
        assertEquals(200, download.status, download.raw);
        assertTrue(download.raw.contains("actionloop-export-v1"), "导出必须带格式标记");
        assertTrue(download.raw.contains("\"workspace\""), "导出必须包含空间信息");
        assertTrue(download.raw.contains("\"tasks\""), "导出必须包含事项集合");

        // 其他用户（非空间成员）不得读取该导出
        Map<String, String> stranger = register();
        Resp denied = req("GET", "/api/v1/exports/" + exportId + "/download",
                stranger.get("access"), null, null);
        assertEquals(404, denied.status, "非成员不得获取导出: " + denied.raw);
    }
}
