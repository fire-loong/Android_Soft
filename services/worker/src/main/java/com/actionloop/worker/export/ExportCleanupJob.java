package com.actionloop.worker.export;

import com.actionloop.api.service.ExportService;
import com.actionloop.worker.reminder.RedisLeaseLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 过期导出对象清理（指导书 13.2「短期下载」）。
 *
 * <p>导出对象含敏感数据，不能依赖用户再次点击下载时才删除；该任务定期把到期对象从存储删除，
 * 并保留 EXPIRED 状态供用户理解失败原因。Redis 租约锁保证多实例只有一个 worker 执行。</p>
 */
@Component
public class ExportCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ExportCleanupJob.class);
    private static final String LOCK_KEY = "actionloop:worker:export:cleanup";

    private final ExportService exportService;
    private final RedisLeaseLock lock;

    @Value("${app.worker.lease-owner:worker-local}")
    private String owner;

    @Value("${app.worker.export-cleanup.enabled:true}")
    private boolean enabled;

    @Value("${app.worker.export-cleanup.lock-ttl-seconds:300}")
    private int lockTtlSeconds;

    public ExportCleanupJob(ExportService exportService, RedisLeaseLock lock) {
        this.exportService = exportService;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${app.worker.export-cleanup.interval-ms:3600000}")
    public void purgeExpiredExports() {
        if (!enabled) {
            return;
        }
        if (!lock.tryAcquire(LOCK_KEY, owner, Duration.ofSeconds(lockTtlSeconds))) {
            log.debug("export cleanup lock held by another worker, skipping tick");
            return;
        }
        try {
            int purged = exportService.purgeExpired(Instant.now());
            if (purged > 0) {
                log.info("export cleanup removed {} expired object(s)", purged);
            }
        } finally {
            lock.release(LOCK_KEY, owner);
        }
    }
}
