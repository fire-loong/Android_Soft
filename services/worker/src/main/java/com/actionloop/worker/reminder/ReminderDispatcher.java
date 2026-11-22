package com.actionloop.worker.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 到期提醒派发循环（指导书 WP03：本地提醒、重启恢复）。
 *
 * <p>每个 tick：抢 Redis 租约锁 → 扫描 state=SCHEDULED 且 trigger_at<=now 的提醒 →
 * 条件更新标记 SENT（并发安全）→ 记日志。崩溃后 TTL 释放锁，重启后首个 tick 会补处理
 * 所有逾期 SCHEDULED 提醒，不丢事件。</p>
 *
 * <p>阶段 3 仅完成调度与状态流转的真实闭环；阶段 4（WP04）将在此处写入 notifications 表
 * 并接入推送通道，当前派发结果以 SENT 状态 + 日志可验证。</p>
 */
@Component
public class ReminderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReminderDispatcher.class);
    private static final String LOCK_KEY = "actionloop:worker:reminder:dispatch";

    private final WorkerReminderRepository repository;
    private final RedisLeaseLock lock;

    @Value("${app.worker.lease-owner:worker-local}")
    private String owner;

    @Value("${app.worker.reminder.lock-ttl-seconds:30}")
    private int lockTtlSeconds;

    public ReminderDispatcher(WorkerReminderRepository repository, RedisLeaseLock lock) {
        this.repository = repository;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${app.worker.reminder.interval-ms:15000}")
    @Transactional
    public void dispatchDue() {
        if (!lock.tryAcquire(LOCK_KEY, owner, Duration.ofSeconds(lockTtlSeconds))) {
            log.debug("reminder dispatch lock held by another worker, skipping tick");
            return;
        }
        try {
            Instant now = Instant.now();
            List<WorkerReminder> due = repository.findByStateAndTriggerAtBefore("SCHEDULED", now);
            if (due.isEmpty()) {
                log.debug("no due reminders at {}", now);
                return;
            }
            int sent = 0;
            int skipped = 0;
            int failed = 0;
            for (WorkerReminder reminder : due) {
                try {
                    int updated = repository.markSent(reminder.getId(), now);
                    if (updated == 1) {
                        sent++;
                        log.info("dispatched reminder id={} task={} triggerAt={} channel={}",
                                reminder.getId(), reminder.getTaskId(), reminder.getTriggerAt(), reminder.getChannel());
                    } else {
                        skipped++; // 已被并发取消或处理
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("dispatch failed for reminder id={} task={}: {}",
                            reminder.getId(), reminder.getTaskId(), e.getMessage(), e);
                    safeMarkFailed(reminder.getId());
                }
            }
            log.info("reminder dispatch tick done: due={} sent={} skipped={} failed={}",
                    due.size(), sent, skipped, failed);
        } finally {
            lock.release(LOCK_KEY, owner);
        }
    }

    private void safeMarkFailed(java.util.UUID id) {
        try {
            repository.markFailed(id, Instant.now());
        } catch (Exception ignored) {
            // 标记失败也出错则留给下个 tick 重试（仍为 SCHEDULED）
        }
    }
}
