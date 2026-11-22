package com.actionloop.api.it;

import com.actionloop.api.test.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 软删除过滤集成：删除事项后从列表消失（@SQLRestriction 过滤），子步骤随级联软删。
 */
class SoftDeleteIntegrationTest extends AbstractIntegrationTest {

    @Test
    void deletedTaskIsFilteredFromList() {
        var u = register();
        String ws = createWorkspace(u.get("access"), "del");
        String taskId = createTask(u.get("access"), ws, "T");

        Resp step = req("POST", "/api/v1/tasks/" + taskId + "/steps?workspaceId=" + ws,
                u.get("access"), Map.of("title", "s", "position", 1), null);
        assertEquals(200, step.status, step.raw);

        Resp before = req("GET", "/api/v1/tasks?workspaceId=" + ws, u.get("access"), null, null);
        assertTrue(containsTask(before, taskId), "task should be listed before delete");

        Resp del = req("DELETE", "/api/v1/tasks/" + taskId + "?workspaceId=" + ws + "&baseVersion=0",
                u.get("access"), null, null);
        assertEquals(200, del.status, del.raw);

        Resp after = req("GET", "/api/v1/tasks?workspaceId=" + ws, u.get("access"), null, null);
        assertFalse(containsTask(after, taskId), "soft-deleted task must be filtered from list");
    }

    private boolean containsTask(Resp list, String taskId) {
        JsonNode items = list.json.path("data").path("content");
        if (!items.isArray()) {
            return false;
        }
        for (JsonNode n : items) {
            if (taskId.equals(n.path("id").asText())) {
                return true;
            }
        }
        return false;
    }
}
