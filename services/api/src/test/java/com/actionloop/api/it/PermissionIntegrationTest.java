package com.actionloop.api.it;

import com.actionloop.api.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限越权矩阵集成：VIEWER 不可提交完成凭证/不可修改事项；非成员不可访问空间资源。
 */
class PermissionIntegrationTest extends AbstractIntegrationTest {

    @Test
    void viewerCannotSubmitCompletion() {
        var owner = register();
        String ws = createWorkspace(owner.get("access"), "perm");
        String taskId = createTask(owner.get("access"), ws, "T");
        Resp step = req("POST", "/api/v1/tasks/" + taskId + "/steps?workspaceId=" + ws,
                owner.get("access"), Map.of("title", "s", "position", 1), null);
        assertEquals(200, step.status, step.raw);
        String stepId = step.json.path("data").path("id").asText();

        var viewer = register();
        invite(owner.get("access"), ws, viewer.get("email"), "VIEWER");
        accept(viewer.get("access"), ws);

        Resp sub = req("POST", "/api/v1/steps/" + stepId + "/completions", viewer.get("access"),
                Map.of("statement", "done"), null);
        assertEquals(403, sub.status, "viewer submit should be forbidden: " + sub.raw);
        assertDeniedCode(sub);
    }

    @Test
    void viewerCannotModifyTask() {
        var owner = register();
        String ws = createWorkspace(owner.get("access"), "perm3");
        String taskId = createTask(owner.get("access"), ws, "T");

        var viewer = register();
        invite(owner.get("access"), ws, viewer.get("email"), "VIEWER");
        accept(viewer.get("access"), ws);

        Resp patch = req("PATCH", "/api/v1/tasks/" + taskId + "?workspaceId=" + ws,
                viewer.get("access"), Map.of("baseVersion", 0, "title", "hacked"), null);
        assertEquals(403, patch.status, "viewer modify should be forbidden: " + patch.raw);
        assertDeniedCode(patch);
    }

    @Test
    void nonMemberCannotAccessTask() {
        var owner = register();
        String ws = createWorkspace(owner.get("access"), "perm2");
        String taskId = createTask(owner.get("access"), ws, "T");

        var other = register(); // 未被邀请
        Resp get = req("GET", "/api/v1/tasks/" + taskId + "?workspaceId=" + ws,
                other.get("access"), null, null);
        assertEquals(403, get.status, "non-member access should be forbidden: " + get.raw);
        assertDeniedCode(get);
    }

    private void assertDeniedCode(Resp r) {
        String code = errCode(r);
        assertTrue(code.equals("ACCESS_DENIED") || code.equals("PERMISSION_DENIED"),
                "expected ACCESS_DENIED/PERMISSION_DENIED, got " + code);
    }
}
