package com.actionloop.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * 完成凭证↔证据资产 关联（completion_assets）。
 */
@Entity
@Table(name = "completion_assets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@IdClass(CompletionAssetId.class)
public class CompletionAsset {

    @Id
    @Column(name = "completion_id", columnDefinition = "uuid", nullable = false)
    private UUID completionId;

    @Id
    @Column(name = "asset_id", columnDefinition = "uuid", nullable = false)
    private UUID assetId;
}
