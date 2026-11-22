package com.actionloop.api.test;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 仅当测试用 PostgreSQL 与 Redis 可达时启用被标注的测试类/方法；否则跳过并给出原因。
 * 用于在无 Docker/无本地集群的环境（如未配置 service 的 CI）下保持 mvn test 通过，
 * 同时在本地真实集群上实际执行集成断言。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(TestInfraCondition.class)
public @interface EnabledIfTestInfra {
}
