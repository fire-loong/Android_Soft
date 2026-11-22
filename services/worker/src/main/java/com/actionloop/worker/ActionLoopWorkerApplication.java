package com.actionloop.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ActionLoop Worker 入口：独立异步进程，消费 jobs 表（FOR UPDATE SKIP LOCKED 租约）与 outbox_event。
 * 不对外暴露业务端口；与 API 共享数据库但不执行迁移（API 负责迁移，避免并发）。
 */
@SpringBootApplication
@EnableScheduling
public class ActionLoopWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ActionLoopWorkerApplication.class, args);
    }
}
