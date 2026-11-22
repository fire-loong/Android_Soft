package com.actionloop.api.domain;

import com.actionloop.shared.domain.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 检索文档：文本 + tsvector（由原生 SQL 维护）。embedding 列在启用 pgvector 后由迁移 006 提供。
 * owner_scope 记录可见性，检索前必须按 ACL 过滤（指导书 12.3）。
 */
@Entity
@Table(name = "search_documents")
@Getter
@Setter
@NoArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class SearchDocument {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "workspace_id", columnDefinition = "uuid", nullable = false)
    private UUID workspaceId;

    @Column(name = "owner_scope", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String ownerScope = "{}";

    @Column(name = "source_item_id", columnDefinition = "uuid")
    private UUID sourceItemId;

    @Column(name = "asset_id", columnDefinition = "uuid")
    private UUID assetId;

    private String title;

    @Column(name = "content_tokens")
    private String contentTokens;

    @Column(name = "raw_text_hash")
    private String rawTextHash;

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "doc_type")
    private String docType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UuidV7.generate();
        }
    }
}
