package com.actionloop.api.it;

import com.actionloop.api.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 事项主流程集成：状态机合法/非法转换（经 /transitions 端点）+ 乐观锁版本冲突。
 */
class TaskFlowIntegrationTest extends AbstractIntegrationTest {

    @Test
    void transitionEndpointEnforcesStateMachine() {
        var u = register();
        String ws = createWorkspace(u.get("access"), "flow");
        String taskId = createTask(u.get("access"), ws, "T");

        // 非法：ACTIVE -> COMPLETED（ACTIVE 仅允许 IN_PROGRESS / PENDING_REVIEW / CANCELLED）
        Resp bad = req("POST", "/api/v1/tasks/" + taskId + "/transitions?workspaceId=" + ws,
                u.get("access"), Map.of("status", "COMPLETED", "baseVersion", 0), null);
        assertEquals(409, bad.status, bad.raw);
        assertEquals("INVALID_STATE_TRANSITION", errCode(bad));

        // 合法：ACTIVE -> IN_PROGRESS
        Resp ok = req("POST", "/api/v1/tasks/" + taskId + "/transitions?workspaceId=" + ws,
                u.get("access"), Map.of("status", "IN_PROGRESS", "baseVersion", 0), null);
        assertEquals(200, ok.status, ok.raw);
        assertEquals("IN_PROGRESS", ok.json.path("data").path("status").asText());
        assertEquals(1, ok.json.path("data").path("version").asInt());
    }

    @Test
    void staleBaseVersionCausesVersionConflict() {
        var u = register();
        String ws = createWorkspace(u.get("access"), "flow2");
        String taskId = createTask(u.get("access"), ws, "T2");

        Resp first = req("PATCH", "/api/v1/tasks/" + taskId + "?workspaceId=" + ws,
                u.get("access"), Map.of("baseVersion", 0, "title", "A"), null);
        assertEquals(200, first.status, first.raw);

        // 用旧版本号再次更新 -> 409 VERSION_CONFLICT
        Resp second = req("PATCH", "/api/v1/tasks/" + taskId + "?workspaceId=" + ws,
                u.get("access"), Map.of("baseVersion", 0, "title", "B"), null);
        assertEquals(409, second.status, second.raw);
        assertEquals("VERSION_CONFLICT", errCode(second));
    }
}
