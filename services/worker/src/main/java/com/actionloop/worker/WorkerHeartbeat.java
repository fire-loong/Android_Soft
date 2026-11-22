package com.actionloop.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker 存活心跳。真实作业调度（AI 抽取、OCR/索引、提醒、清理、导出）在阶段 2/4 接入：
 * 以租约抢占 jobs 表（FOR UPDATE SKIP LOCKED），失败进入重试退避，超过 max_attempts 转 DEAD_LETTER。
 */
@Component
public class WorkerHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(WorkerHeartbeat.class);

    @Value("${app.worker.lease-owner:worker-local}")
    private String leaseOwner;

    @Scheduled(fixedDelay = 30_000)
    public void beat() {
        log.info("worker heartbeat owner={}", leaseOwner);
    }
}
