package com.actionloop.api.service.storage;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * 生产 S3 兼容私有对象存储（指导书 11.6）。默认不启用（app.storage.type=s3 时装配）。
 * 桶禁止公开；上传/下载均使用预签名 URL，业务 API 不代理字节流。
 */
public class S3FileStorageProvider implements FileStorageProvider {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3FileStorageProvider(StorageProperties props) {
        StorageProperties.S3 cfg = props.getS3();
        if (cfg.getBucket() == null || cfg.getBucket().isBlank()) {
            throw new IllegalStateException("app.storage.s3.bucket 未配置");
        }
        this.bucket = cfg.getBucket();
        S3Configuration s3cfg = S3Configuration.builder()
                .pathStyleAccessEnabled(cfg.isPathStyleAccess())
                .build();
        var b = S3Client.builder()
                .region(Region.of(cfg.getRegion()))
                .serviceConfiguration(s3cfg);
        if (cfg.getEndpoint() != null && !cfg.getEndpoint().isBlank()) {
            b.endpointOverride(URI.create(cfg.getEndpoint()));
        }
        this.s3 = b.build();

        var pb = S3Presigner.builder()
                .region(Region.of(cfg.getRegion()))
                .serviceConfiguration(s3cfg);
        if (cfg.getEndpoint() != null && !cfg.getEndpoint().isBlank()) {
            pb.endpointOverride(URI.create(cfg.getEndpoint()));
        }
        this.presigner = pb.build();
    }

    @Override
    public String providerType() {
        return "s3";
    }

    @Override
    public void putObject(String key, InputStream content, long size) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromInputStream(content, size));
    }

    @Override
    public InputStream getObject(String key) {
        ResponseInputStream<GetObjectResponse> in = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
        return in;
    }

    @Override
    public void deleteObject(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public long size(String key) {
        return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
                .contentLength();
    }

    @Override
    public String computeSha256(String key) throws IOException {
        try {
            String checksum = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
                    .checksumSHA256();
            if (checksum != null && !checksum.isBlank()) {
                // S3 checksumSHA256 为 Base64(原始摘要)；AssetService 以 hex 比较，必须转换
                return java.util.HexFormat.of().formatHex(java.util.Base64.getDecoder().decode(checksum));
            }
        } catch (NoSuchKeyException e) {
            return null;
        }
        // 上传未携带校验和时，流式重算：完整性校验不可因 head 字段缺失而跳过（指导书 11.6）。
        try (InputStream in = getObject(key)) {
            return sha256Hex(in);
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    private static String sha256Hex(InputStream in) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    @Override
    public String presignPut(String key, Duration ttl) {
        PresignedPutObjectRequest req = presigner.presignPutObject(b -> b
                .signatureDuration(ttl)
                .putObjectRequest(p -> p.bucket(bucket).key(key)));
        return req.url().toString();
    }

    @Override
    public String presignGet(String key, Duration ttl) {
        PresignedGetObjectRequest req = presigner.presignGetObject(b -> b
                .signatureDuration(ttl)
                .getObjectRequest(g -> g.bucket(bucket).key(key)));
        return req.url().toString();
    }

    /** 供服务端完整性校验使用（指导书 11.6）。 */
    public BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "对象不存在");
    }
}
