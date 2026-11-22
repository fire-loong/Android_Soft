package com.actionloop.api.it;

import com.actionloop.api.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 幂等重放集成：相同 Idempotency-Key 重复 POST 返回同一资源 id，不重复创建。
 */
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Test
    void replayWithSameKeyReturnsSameId() {
        var u = register();
        String ws = createWorkspace(u.get("access"), "idem");
        String key = unique("key");
        Map<String, Object> body = Map.of("workspaceId", ws, "title", "I");

        Resp first = req("POST", "/api/v1/tasks", u.get("access"), body, key);
        Resp second = req("POST", "/api/v1/tasks", u.get("access"), body, key);

        assertEquals(200, first.status, first.raw);
        assertEquals(200, second.status, second.raw);
        assertEquals(
                first.json.path("data").path("id").asText(),
                second.json.path("data").path("id").asText(),
                "replay must return the same task id");
    }
}
