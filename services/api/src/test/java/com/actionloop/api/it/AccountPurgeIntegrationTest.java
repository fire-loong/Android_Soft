package com.actionloop.api.it;

import com.actionloop.api.domain.EvidenceAsset;
import com.actionloop.api.repository.EvidenceAssetRepository;
import com.actionloop.api.service.AccountPurgeService;
import com.actionloop.api.service.storage.StorageProperties;
import com.actionloop.api.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注销到期清除（指导书 15.5）：匿名化墓碑、删除个人空间与对象存储、撤销设备、移除其它空间成员关系；
 * 若冷却期内又成为共享空间所有者则拒绝清除（下轮重试）。
 */
class AccountPurgeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AccountPurgeService purgeService;

    @Autowired
    private EvidenceAssetRepository assetRepository;

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void purgeAnonymizesUserDeletesOwnedWorkspaceAndRevokesSessions() throws Exception {
        var u = register();
        UUID userId = UUID.fromString(u.get("userId"));

        Resp me = req("GET", "/api/v1/me", u.get("access"), null, null);
        assertEquals(200, me.status, me.raw);
        String wsId = me.json.path("data").path("workspaces").get(0).path("id").asText();
        UUID workspaceId = UUID.fromString(wsId);
        createTask(u.get("access"), wsId, unique("purge-task"));

        String storageKey = "purge-test/" + UUID.randomUUID() + "/evidence.bin";
        Path root = Paths.get(storageProperties.getLocal().getRoot()).toAbsolutePath().normalize();
        Path stored = root.resolve(storageKey);
        Files.createDirectories(stored.getParent());
        Files.writeString(stored, "sensitive-evidence");

        EvidenceAsset asset = new EvidenceAsset();
        asset.setWorkspaceId(workspaceId);
        asset.setSha256("a".repeat(64));
        asset.setMime("application/octet-stream");
        asset.setSizeBytes(18L);
        asset.setStorageKey(storageKey);
        assetRepository.save(asset);

        Resp requested = req("DELETE", "/api/v1/me", u.get("access"), Map.of("password", "Passw0rd!"), null);
        assertEquals(202, requested.status, requested.raw);

        Instant afterCooldown = Instant.now().plus(Duration.ofDays(400));
        assertTrue(purgeService.purgeDue(afterCooldown) >= 1, "到期账户应被清除");
        assertEquals(0, purgeService.purgeDue(afterCooldown), "重复清除应幂等，不再处理已注销账户");

        assertEquals(401, req("GET", "/api/v1/me", u.get("access"), null, null).status, "旧 access token 立即失效");
        assertEquals(401, req("POST", "/api/v1/auth/refresh", null,
                Map.of("refreshToken", u.get("refresh")), null).status, "旧 refresh token 立即失效");
        Resp login = req("POST", "/api/v1/auth/login", null, Map.of(
                "login", u.get("email"), "password", "Passw0rd!", "deviceName", "w", "devicePlatform", "WEB"), null);
        assertEquals(401, login.status, "注销后原凭据不可登录: " + login.raw);

        Map<String, Object> row = jdbc.queryForMap(
                "select status, email, username, password_hash from users where id = ?", userId);
        assertEquals("DELETED", row.get("status"));
        assertTrue(((String) row.get("email")).startsWith("deleted+"), "邮箱必须匿名化");
        assertTrue(((String) row.get("username")).startsWith("deleted_"), "用户名必须匿名化");
        assertFalse(((String) row.get("password_hash")).startsWith("$argon2"), "必须清除原密码哈希");

        assertEquals(0, count("select count(*) from workspaces where owner_id = ?", userId));
        assertEquals(0, count("select count(*) from tasks where workspace_id = ?", workspaceId));
        assertEquals(0, count("select count(*) from devices where user_id = ? and status = 'ACTIVE'", userId));
        assertEquals(0, count("select count(*) from evidence_assets where workspace_id = ?", workspaceId));
        assertFalse(Files.exists(stored), "注销后对象存储内容必须删除");
        assertTrue(count("select count(*) from audit_logs where actor_id = ? and action = 'account.delete.purge'",
                userId) >= 1, "清除必须写审计");
    }

    @Test
    void purgeRemovesMembershipInSharedWorkspaceButKeepsWorkspace() {
        var owner = register();
        String wsId = createWorkspace(owner.get("access"), unique("team"));
        UUID workspaceId = UUID.fromString(wsId);
        var member = register();
        UUID memberId = UUID.fromString(member.get("userId"));
        invite(owner.get("access"), wsId, member.get("email"), "MEMBER");
        accept(member.get("access"), wsId);

        assertEquals(202, req("DELETE", "/api/v1/me", member.get("access"),
                Map.of("password", "Passw0rd!"), null).status);

        purgeService.purgeDue(Instant.now().plus(Duration.ofDays(400)));

        assertEquals(0, count("select count(*) from memberships where user_id = ? and workspace_id = ? "
                + "and status = 'ACTIVE'", memberId, workspaceId));
        assertEquals("REMOVED", jdbc.queryForObject(
                "select status from memberships where user_id = ? and workspace_id = ?", String.class,
                memberId, workspaceId));
        assertEquals(1, count("select count(*) from workspaces where id = ?", workspaceId),
                "团队空间属于其他成员，不得删除");
        assertEquals(200, req("GET", "/api/v1/workspaces/" + wsId, owner.get("access"), null, null).status,
                "空间所有者仍可访问");
    }

    @Test
    void purgeIsBlockedIfOwnershipBecomesSharedDuringCooldown() {
        var owner = register();
        UUID ownerId = UUID.fromString(owner.get("userId"));
        assertEquals(202, req("DELETE", "/api/v1/me", owner.get("access"),
                Map.of("password", "Passw0rd!"), null).status);

        String wsId = createWorkspace(owner.get("access"), unique("team-late"));
        var member = register();
        invite(owner.get("access"), wsId, member.get("email"), "MEMBER");
        accept(member.get("access"), wsId);

        purgeService.purgeDue(Instant.now().plus(Duration.ofDays(400)));

        assertEquals("ACTIVE", jdbc.queryForObject("select status from users where id = ?", String.class, ownerId),
                "共享空间所有者不得被清除");
        assertEquals(1, count("select count(*) from workspaces where id = ?", UUID.fromString(wsId)));
        assertTrue(count("select count(*) from audit_logs where actor_id = ? and action = 'account.delete.purge' "
                + "and result = 'DENIED'", ownerId) >= 1, "被阻塞的清除必须写 DENIED 审计");
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
