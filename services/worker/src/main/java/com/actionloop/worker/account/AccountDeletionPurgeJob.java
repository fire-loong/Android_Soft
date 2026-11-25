package com.actionloop.worker.account;

import com.actionloop.api.service.AccountPurgeService;
import com.actionloop.worker.reminder.RedisLeaseLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 注销到期清除定时任务（指导书 15.5：注销冷却结束后真正删除数据）。
 *
 * <p>Redis 租约锁保证多实例只有一个 worker 扫描；每个账户在独立事务内清除，
 * 单账户失败不影响其它账户，并会在下个周期重试（计划时间仍在过去）。</p>
 */
@Component
public class AccountDeletionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionPurgeJob.class);
    private static final String LOCK_KEY = "actionloop:worker:account:purge";

    private final AccountPurgeService purgeService;
    private final RedisLeaseLock lock;

    @Value("${app.worker.lease-owner:worker-local}")
    private String owner;

    @Value("${app.worker.account-deletion.enabled:true}")
    private boolean enabled;

    @Value("${app.worker.account-deletion.lock-ttl-seconds:300}")
    private int lockTtlSeconds;

    public AccountDeletionPurgeJob(AccountPurgeService purgeService, RedisLeaseLock lock) {
        this.purgeService = purgeService;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${app.worker.account-deletion.interval-ms:600000}")
    public void purgeDueAccounts() {
        if (!enabled) {
            return;
        }
        if (!lock.tryAcquire(LOCK_KEY, owner, Duration.ofSeconds(lockTtlSeconds))) {
            log.debug("account purge lock held by another worker, skipping tick");
            return;
        }
        try {
            int purged = purgeService.purgeDue(Instant.now());
            if (purged > 0) {
                log.info("account purge job removed {} due account(s)", purged);
            }
        } finally {
            lock.release(LOCK_KEY, owner);
        }
    }
}
