package com.actionloop.api.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * 按 workspace + 用户 + 来源隔离的派生结果缓存。
 * 不允许跨用户读取；来源软删除时主动清除，物理删除由外键级联兜底。
 */
@Service
public class AiResultCacheService {

    private static final Logger log = LoggerFactory.getLogger(AiResultCacheService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public AiResultCacheService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(PROPAGATION_REQUIRES_NEW);
    }

    public Optional<CandidateExtraction> find(ExtractionRequest request, String modelVersion,
                                              String promptVersion, String schemaVersion) {
        if (!cacheable(request)) {
            return Optional.empty();
        }
        String sql = """
                SELECT result_json FROM ai_result_cache
                WHERE workspace_id = ? AND requested_by = ? AND source_item_id = ?
                  AND input_hash = ? AND model_version = ? AND prompt_version = ? AND schema_version = ?
                """;
        try {
            return transactionTemplate.execute(status -> jdbcTemplate.query(sql, (ResultSet rs) -> {
                if (!rs.next()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(objectMapper.readValue(rs.getString(1), CandidateExtraction.class));
                } catch (Exception e) {
                    log.warn("Discarding unreadable AI cache entry");
                    return Optional.empty();
                }
            }, request.workspaceId(), request.requestedBy(), request.sourceItemId(), inputHash(request),
                    modelVersion, promptVersion, schemaVersion));
        } catch (RuntimeException e) {
            log.warn("AI cache read unavailable: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void store(ExtractionRequest request, CandidateExtraction extraction) {
        if (!cacheable(request)) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    storeInNewTransaction(request, extraction);
                }
            });
            return;
        }
        storeInNewTransaction(request, extraction);
    }

    private void storeInNewTransaction(ExtractionRequest request, CandidateExtraction extraction) {
        String sql = """
                INSERT INTO ai_result_cache (
                    workspace_id, requested_by, source_item_id, input_hash,
                    model_version, prompt_version, schema_version, result_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT ON CONSTRAINT uniq_ai_result_private_key
                DO UPDATE SET result_json = EXCLUDED.result_json, last_used_at = now()
                """;
        try {
            String resultJson = objectMapper.writeValueAsString(extraction);
            transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(sql,
                    request.workspaceId(), request.requestedBy(), request.sourceItemId(), inputHash(request),
                    extraction.modelVersion(), extraction.promptVersion(), extraction.schemaVersion(), resultJson));
        } catch (Exception e) {
            log.warn("AI cache write unavailable: {}", e.getClass().getSimpleName());
        }
    }

    public void deleteForSource(java.util.UUID workspaceId, java.util.UUID sourceItemId) {
        jdbcTemplate.update("DELETE FROM ai_result_cache WHERE workspace_id = ? AND source_item_id = ?",
                workspaceId, sourceItemId);
    }

    static String inputHash(ExtractionRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, request.text());
            update(digest, Objects.toString(request.receivedAt(), ""));
            update(digest, request.timezone());
            update(digest, request.sourceApp());
            update(digest, request.sourceTitle());
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static boolean cacheable(ExtractionRequest request) {
        return request != null && request.workspaceId() != null && request.requestedBy() != null
                && request.sourceItemId() != null;
    }
}
