package com.actionloop.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import com.actionloop.api.service.AuthService;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ActionLoop Worker 入口：独立异步进程，消费 jobs 表（FOR UPDATE SKIP LOCKED 租约）与 outbox_event。
 * 不对外暴露业务端口；与 API 共享数据库但不执行迁移（API 负责迁移，避免并发）。
 *
 * <p>Worker 复用 API 的 service/repository/AI/OCR library 构件，但显式排除 web/security/config
 * 层，避免在非 Web 上下文中实例化 Servlet 过滤器、控制器和 SecurityFilterChain。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {
                "com.actionloop.api.ai",
                "com.actionloop.api.job",
                "com.actionloop.api.realtime",
                "com.actionloop.api.service",
                "com.actionloop.api.service.storage",
                "com.actionloop.worker"
        },
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AuthService.class)
        })
@EnableJpaRepositories(basePackages = {"com.actionloop.api.repository", "com.actionloop.worker"})
@EntityScan(basePackages = {"com.actionloop.api.domain", "com.actionloop.worker"})
@ConfigurationPropertiesScan(basePackages = {"com.actionloop.api", "com.actionloop.worker"})
@EnableScheduling
public class ActionLoopWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ActionLoopWorkerApplication.class, args);
    }
}
