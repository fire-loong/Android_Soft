package com.actionloop.worker.reminder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Worker 侧轻量映射：与 API 共享同一张 reminders 表，但不拉入 API 的 web/安全上下文。
 * 字段与迁移 V202611210002 严格一致（无乐观锁列）。worker 只读 + 条件更新 state。
 */
@Entity
@Table(name = "reminders")
public class WorkerReminder {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "task_id", columnDefinition = "uuid", nullable = false)
    private UUID taskId;

    @Column(name = "trigger_at", nullable = false)
    private Instant triggerAt;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String state;

    @Column(name = "dedupe_key", nullable = false)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }
    public Instant getTriggerAt() { return triggerAt; }
    public void setTriggerAt(Instant triggerAt) { this.triggerAt = triggerAt; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
