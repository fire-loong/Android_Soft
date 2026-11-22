package com.actionloop.api.service.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 私有对象存储配置（指导书 11.6）。
 * 默认 type=local 走本地磁盘（开发期/无 MinIO 时，见 ADR-001）；生产切换 type=s3 走 S3 兼容桶。
 * 桶禁止公开，下载一律短期签名。
 */
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** local | s3 */
    private String type = "local";
    private long maxFileSizeBytes = 50L * 1024 * 1024;
    private long quotaBytesPerWorkspace = 5L * 1024 * 1024 * 1024;
    /** 上传会话/签名下载默认 TTL（分钟） */
    private int uploadTtlMinutes = 60;
    private int downloadTtlMinutes = 10;
    private Local local = new Local();
    private S3 s3 = new S3();

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
    public void setMaxFileSizeBytes(long v) { this.maxFileSizeBytes = v; }
    public long getQuotaBytesPerWorkspace() { return quotaBytesPerWorkspace; }
    public void setQuotaBytesPerWorkspace(long v) { this.quotaBytesPerWorkspace = v; }
    public int getUploadTtlMinutes() { return uploadTtlMinutes; }
    public void setUploadTtlMinutes(int v) { this.uploadTtlMinutes = v; }
    public int getDownloadTtlMinutes() { return downloadTtlMinutes; }
    public void setDownloadTtlMinutes(int v) { this.downloadTtlMinutes = v; }
    public Local getLocal() { return local; }
    public void setLocal(Local local) { this.local = local; }
    public S3 getS3() { return s3; }
    public void setS3(S3 s3) { this.s3 = s3; }

    public static class Local {
        /** 本地磁盘根目录（相对服务进程工作目录或绝对路径） */
        private String root = "data/storage";
        /** 对外可访问的 API 基址，用于生成本地代理上传/下载 URL；为空时从请求推断 */
        private String publicBaseUrl = "";
        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
        public String getPublicBaseUrl() { return publicBaseUrl; }
        public void setPublicBaseUrl(String v) { this.publicBaseUrl = v; }
    }

    public static class S3 {
        private String bucket = "";
        private String region = "us-east-1";
        private String endpoint = "";
        private boolean pathStyleAccess = false;
        public String getBucket() { return bucket; }
        public void setBucket(String v) { this.bucket = v; }
        public String getRegion() { return region; }
        public void setRegion(String v) { this.region = v; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String v) { this.endpoint = v; }
        public boolean isPathStyleAccess() { return pathStyleAccess; }
        public void setPathStyleAccess(boolean v) { this.pathStyleAccess = v; }
    }
}
