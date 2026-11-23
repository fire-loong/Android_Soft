package com.actionloop.api.realtime;

/** outbox relay 的发布出口；生产为 Redis 发布，测试可替换为可控失败实现。 */
public interface WorkspaceEventPublisher {

    void publish(WorkspaceEventMessage message);
}
