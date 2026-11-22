package com.actionloop.api.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/**
 * 私有对象存储抽象（指导书 11.6）。实现：LocalFileStorageProvider（开发/本机）与 S3FileStorageProvider（生产）。
 * key 不暴露真实文件名；下载走短期签名。s3 的预签名由本接口返回；local 的上传/下载 URL 由 AssetService 基于 API 代理签发。
 */
public interface FileStorageProvider {

    String providerType();

    /** 直接写入对象（local 代理端点、测试、以及 complete 时从临时对象复制到最终对象使用）。 */
    void putObject(String key, InputStream content, long size) throws IOException;

    InputStream getObject(String key) throws IOException;

    void deleteObject(String key);

    boolean exists(String key);

    long size(String key);

    /** 计算对象的 SHA-256（小写十六进制）。s3 若无法服务端计算返回 null。 */
    String computeSha256(String key) throws IOException;

    /** s3 返回预签名 PUT URL；local 返回 null。 */
    String presignPut(String key, Duration ttl);

    /** s3 返回预签名 GET URL；local 返回 null。 */
    String presignGet(String key, Duration ttl);
}
