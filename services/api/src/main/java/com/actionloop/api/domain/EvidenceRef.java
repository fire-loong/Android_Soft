package com.actionloop.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "evidence_refs")
@Getter
@Setter
@NoArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class EvidenceRef extends VersionedAggregate {

    @Column(name = "workspace_id", columnDefinition = "uuid", nullable = false)
    private UUID workspaceId;

    @Column(name = "source_item_id", columnDefinition = "uuid")
    private UUID sourceItemId;

    @Column(name = "asset_id", columnDefinition = "uuid")
    private UUID assetId;

    @Column(name = "page_no")
    private Integer pageNo;

    @Column(name = "bbox_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String bboxJson;

    @Column(name = "char_start")
    private Integer charStart;

    @Column(name = "char_end")
    private Integer charEnd;

    @Column(name = "quote_text", nullable = false)
    private String quoteText;

    @Column(name = "content_hash")
    private String contentHash;
}
