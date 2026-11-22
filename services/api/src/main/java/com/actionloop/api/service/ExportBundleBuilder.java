package com.actionloop.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 空间导出快照构建（指导书 11.7 / WP13）。
 * 在独立只读事务中执行：任一查询失败只回滚快照事务，不会污染导出任务的状态更新事务
 * （PostgreSQL 事务一旦报错即中止，必须隔离，否则任务状态无法落库为 FAILED）。
 */
@Component
public class ExportBundleBuilder {

    private static final int MAX_ROWS = 5000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ExportBundleBuilder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public byte[] build(UUID workspaceId) throws Exception {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle(workspaceId));
    }

    private Map<String, Object> bundle(UUID workspaceId) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("format", "actionloop-export-v1");
        bundle.put("exportedAt", Instant.now().toString());
        bundle.put("workspace", jdbcTemplate.queryForMap(
                "SELECT id, name, created_at FROM workspaces WHERE id = ?", workspaceId));
        put(bundle, "members", "SELECT m.user_id, m.role, m.status, m.joined_at FROM memberships m WHERE m.workspace_id = ? AND m.status = 'ACTIVE' AND m.deleted_at IS NULL ORDER BY m.user_id", workspaceId);
        put(bundle, "tasks", "SELECT id, title, description, status, owner_id, assignee_id, due_at, due_date, time_precision, derivation, version, created_at FROM tasks WHERE workspace_id = ? AND deleted_at IS NULL ORDER BY id", workspaceId);
        put(bundle, "task_steps", "SELECT s.id, s.task_id, s.title, s.position, s.status, s.assignee_id FROM task_steps s JOIN tasks t ON t.id = s.task_id WHERE t.workspace_id = ? AND s.deleted_at IS NULL AND t.deleted_at IS NULL ORDER BY s.position, s.id", workspaceId);
        put(bundle, "materials", "SELECT m.id, m.task_id, m.name, m.state FROM materials m JOIN tasks t ON t.id = m.task_id WHERE t.workspace_id = ? AND m.deleted_at IS NULL AND t.deleted_at IS NULL ORDER BY m.id", workspaceId);
        put(bundle, "reminders", "SELECT r.id, r.task_id, r.trigger_at, r.channel, r.state FROM reminders r JOIN tasks t ON t.id = r.task_id WHERE t.workspace_id = ? AND t.deleted_at IS NULL ORDER BY r.trigger_at, r.id", workspaceId);
        put(bundle, "completions", "SELECT c.id, s.task_id, c.submitter_id, c.review_state, c.submitted_at FROM completions c JOIN task_steps s ON s.id = c.task_step_id JOIN tasks t ON t.id = s.task_id WHERE t.workspace_id = ? AND s.deleted_at IS NULL AND t.deleted_at IS NULL ORDER BY c.submitted_at, c.id", workspaceId);
        put(bundle, "comments", "SELECT c.id, c.task_id, c.author_id, c.body, c.created_at FROM comments c WHERE c.workspace_id = ? AND c.deleted_at IS NULL ORDER BY c.created_at, c.id", workspaceId);
        put(bundle, "sources", "SELECT id, source_type, source_app, source_title, content_preview, privacy_level, received_at FROM source_items WHERE workspace_id = ? AND deleted_at IS NULL ORDER BY received_at, id", workspaceId);
        put(bundle, "candidates", "SELECT id, title, summary, due_at, confidence, status, derivation FROM candidate_tasks WHERE workspace_id = ? AND deleted_at IS NULL ORDER BY created_at, id", workspaceId);
        put(bundle, "process_templates", "SELECT id, title, region_code, current_version_id FROM process_templates WHERE owner_workspace_id = ? AND deleted_at IS NULL ORDER BY id", workspaceId);
        put(bundle, "process_versions", "SELECT v.id, v.template_id, v.version_no, v.review_state, v.published_at FROM process_versions v JOIN process_templates t ON t.id = v.template_id WHERE t.owner_workspace_id = ? AND t.deleted_at IS NULL AND v.deleted_at IS NULL ORDER BY v.version_no, v.id", workspaceId);
        put(bundle, "audit_logs", "SELECT id, actor_id, action, object_type, object_id, result, created_at FROM audit_logs WHERE workspace_id = ? ORDER BY created_at DESC, id DESC", workspaceId);
        return bundle;
    }

    private void put(Map<String, Object> bundle, String name, String sql, UUID workspaceId) {
        List<Map<String, Object>> rows = new ArrayList<>(jdbcTemplate.queryForList(sql + " LIMIT " + MAX_ROWS, workspaceId));
        bundle.put(name, rows);
    }
}
