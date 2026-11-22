package com.actionloop.api.it;

import com.actionloop.api.test.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程工作室与协助只读信令（指导书 6.x / 8.x / WP06 / WP11）。
 * 重点锁定：步骤来源引用写入、个人未核验发布、实例化、协助 mode 白名单、控制类信令拒绝。
 */
class ProcessAssistIntegrationTest extends AbstractIntegrationTest {

    @Test
    void processPublishInstantiateAndAssistReadOnlySignals() {
        Map<String, String> owner = register();
        String token = owner.get("access");
        String ws = createWorkspace(token, unique("流程空间"));

        Map<String, Object> share = new LinkedHashMap<>();
        share.put("workspaceId", ws);
        share.put("sourceType", "LINK");
        share.put("sourceKey", unique("key"));
        share.put("title", "学校助学金官方页面");
        share.put("linkUrl", "https://example.edu/scholarship");
        share.put("text", "助学金申请需在学生事务中心提交申请表与成绩单");
        Resp shareResp = req("POST", "/api/v1/sources/share", token, share, null);
        assertEquals(200, shareResp.status, shareResp.raw);
        String evidenceId = shareResp.json.path("data").path("evidence").get(0).path("id").asText();

        Resp tpl = req("POST", "/api/v1/processes", token,
                Map.of("workspaceId", ws, "title", unique("助学金流程"), "regionCode", "CN-ZJ"), null);
        assertEquals(200, tpl.status, tpl.raw);
        String templateId = tpl.json.path("data").path("id").asText();

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("instruction", "在官方入口填写申请表");
        step.put("required", true);
        step.put("officialEntryUrl", "https://example.edu/scholarship");
        step.put("sourceRefIds", List.of(evidenceId));
        Resp version = req("POST", "/api/v1/processes/" + templateId + "/versions", token,
                Map.of("steps", List.of(step)), null);
        assertEquals(200, version.status, version.raw);
        String versionId = version.json.path("data").path("id").asText();
        assertEquals(1, version.json.path("data").path("steps").size(), "步骤必须落库");
        assertEquals(1, version.json.path("data").path("steps").get(0).path("sourceRefIds").size(),
                "步骤来源引用必须落库");

        // 无不同复核人且未显式允许个人未核验 → 拒绝
        Resp blocked = req("POST", "/api/v1/process-versions/" + versionId + "/publish", token,
                Map.of("allowPersonalUnverified", false), null);
        assertEquals(422, blocked.status, blocked.raw);

        Resp published = req("POST", "/api/v1/process-versions/" + versionId + "/publish", token,
                Map.of("allowPersonalUnverified", true, "reviewerNote", "个人经验"), null);
        assertEquals(200, published.status, published.raw);
        assertEquals("PERSONAL_UNVERIFIED", published.json.path("data").path("reviewState").asText());

        Resp list = req("GET", "/api/v1/processes?workspaceId=" + ws, token, null, null);
        assertEquals(200, list.status, list.raw);
        assertEquals(1, list.json.path("data").path("items").get(0).path("versions").size(),
                "模板列表必须返回版本元数据，前端不能显示 0 个版本");

        Resp inst = req("POST", "/api/v1/process-versions/" + versionId + "/instantiate", token,
                Map.of("workspaceId", ws, "title", unique("助学金事项")), null);
        assertEquals(200, inst.status, inst.raw);
        String taskId = inst.json.path("data").path("id").asText();
        Resp steps = req("GET", "/api/v1/tasks/" + taskId + "/steps?workspaceId=" + ws, token, null, null);
        assertEquals(1, steps.json.path("data").size(), "实例化必须固化流程步骤");

        // 协助：mode 只允许数据库白名单值
        Resp badMode = req("POST", "/api/v1/assist/sessions", token,
                Map.of("workspaceId", ws, "mode", "VIEW_ONLY"), null);
        assertEquals(400, badMode.status, badMode.raw);

        // 远程协助未指定协助者：开放邀请等待他人接受，未激活前不得发送信令
        Resp remote = req("POST", "/api/v1/assist/sessions", token,
                Map.of("workspaceId", ws, "mode", "REMOTE_ASSIST",
                        "scopes", List.of("VIEW_SCREEN", "HIGHLIGHT", "TEXT_HINT"), "expiresMinutes", 10), null);
        assertEquals(200, remote.status, remote.raw);
        String remoteId = remote.json.path("data").path("id").asText();
        assertEquals("IDLE", remote.json.path("data").path("state").asText());
        Resp remoteHint = req("POST", "/api/v1/assist/sessions/" + remoteId + "/signal", token,
                Map.of("eventType", "TEXT_HINT", "payload", Map.of("text", "未激活")), null);
        assertEquals(409, remoteHint.status, "未激活的远程协助不得发送信令: " + remoteHint.raw);

        // 本机引导无远程参与者，创建即激活
        Resp assist = req("POST", "/api/v1/assist/sessions", token,
                Map.of("workspaceId", ws, "mode", "LOCAL_GUIDANCE",
                        "scopes", List.of("HIGHLIGHT", "TEXT_HINT"), "expiresMinutes", 10), null);
        assertEquals(200, assist.status, assist.raw);
        String sessionId = assist.json.path("data").path("id").asText();
        assertEquals("LOCAL_GUIDANCE", assist.json.path("data").path("mode").asText());
        assertEquals("ACTIVE", assist.json.path("data").path("state").asText());

        Resp hint = req("POST", "/api/v1/assist/sessions/" + sessionId + "/signal", token,
                Map.of("eventType", "TEXT_HINT", "payload", Map.of("text", "请看高亮处")), null);
        assertEquals(200, hint.status, hint.raw);

        Resp click = req("POST", "/api/v1/assist/sessions/" + sessionId + "/signal", token,
                Map.of("eventType", "CLICK", "payload", Map.of("x", 0.5, "y", 0.5)), null);
        assertEquals(403, click.status, "控制类信令必须被拒绝: " + click.raw);

        Resp stop = req("POST", "/api/v1/assist/sessions/" + sessionId + "/stop", token,
                Map.of("reason", "测试结束"), null);
        assertEquals(200, stop.status, stop.raw);
        assertEquals("STOPPED", stop.json.path("data").path("state").asText());
    }
}
