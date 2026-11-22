package com.actionloop.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ActionLoop API 入口：模块化单体 REST API。
 * 业务写操作在同一事务内完成权限检查、版本检查、业务表更新、审计与 Outbox 写入（指导书 11.3）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ActionLoopApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ActionLoopApiApplication.class, args);
    }
}
