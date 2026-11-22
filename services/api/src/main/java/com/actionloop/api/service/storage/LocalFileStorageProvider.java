package com.actionloop.api.service.storage;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 本地磁盘对象存储（开发期与无 MinIO 环境，见 ADR-001）。
 * 生产路径请切换 app.storage.type=s3。key 做路径穿越防护，根目录在构造时创建。
 */
public class LocalFileStorageProvider implements FileStorageProvider {

    private final Path root;

    public LocalFileStorageProvider(StorageProperties props) {
        this.root = Paths.get(props.getLocal().getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地存储根目录: " + root, e);
        }
    }

    @Override
    public String providerType() {
        return "local";
    }

    private Path resolveSafe(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("..")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "非法对象键");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "对象键越界");
        }
        return resolved;
    }

    @Override
    public void putObject(String key, InputStream content, long size) throws IOException {
        Path target = resolveSafe(key);
        Files.createDirectories(target.getParent());
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public InputStream getObject(String key) throws IOException {
        return Files.newInputStream(resolveSafe(key));
    }

    @Override
    public void deleteObject(String key) {
        try {
            Files.deleteIfExists(resolveSafe(key));
        } catch (IOException e) {
            throw new IllegalStateException("删除对象失败: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolveSafe(key));
    }

    @Override
    public long size(String key) {
        try {
            return Files.size(resolveSafe(key));
        } catch (IOException e) {
            throw new IllegalStateException("读取对象大小失败: " + key, e);
        }
    }

    @Override
    public String computeSha256(String key) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = getObject(key)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Override
    public String presignPut(String key, Duration ttl) {
        return null;
    }

    @Override
    public String presignGet(String key, Duration ttl) {
        return null;
    }
}
