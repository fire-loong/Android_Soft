package com.actionloop.api.config;

import com.actionloop.api.realtime.RedisWorkspaceEventPublisher;
import com.actionloop.api.web.WorkspaceEventRedisSubscriber;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** API 进程订阅空间事件频道；worker 非 Web 上下文不扫描此配置。 */
@Configuration
@ConditionalOnProperty(prefix = "app.websocket", name = "relay-enabled", havingValue = "true")
public class WorkspaceEventRedisConfig {

    @Bean
    public RedisMessageListenerContainer workspaceEventListenerContainer(RedisConnectionFactory connectionFactory,
                                                                        WorkspaceEventRedisSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(RedisWorkspaceEventPublisher.CHANNEL));
        return container;
    }
}
