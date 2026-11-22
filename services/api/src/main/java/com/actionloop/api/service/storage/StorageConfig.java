package com.actionloop.api.service.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配私有对象存储 Provider。默认 local（开发/本机磁盘），设置 app.storage.type=s3 时启用生产 S3 路径。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
    public FileStorageProvider s3FileStorageProvider(StorageProperties props) {
        return new S3FileStorageProvider(props);
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
    public FileStorageProvider localFileStorageProvider(StorageProperties props) {
        return new LocalFileStorageProvider(props);
    }
}
