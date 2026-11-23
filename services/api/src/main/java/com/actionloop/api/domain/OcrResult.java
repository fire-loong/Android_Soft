package com.actionloop.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** 一次资产、模型与提示词组合对应的不可变纯文本 OCR 结果。 */
@Entity
@Table(name = "ocr_results")
@Getter
@Setter
@NoArgsConstructor
public class OcrResult extends TimestampedEntity {

    @Column(name = "asset_id", columnDefinition = "uuid", nullable = false)
    private UUID assetId;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "input_sha256", nullable = false, length = 64)
    private String inputSha256;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "image_width")
    private Integer imageWidth;

    @Column(name = "image_height")
    private Integer imageHeight;

    /** 1 表示整图一次识别；大于 1 表示长截图分段识别，段明细见 ocr_segments。 */
    @Column(name = "segment_count", nullable = false)
    private Integer segmentCount = 1;

    @Column(name = "coordinates_available", nullable = false)
    private boolean coordinatesAvailable = false;

    @Column(name = "created_by", columnDefinition = "uuid", nullable = false)
    private UUID createdBy;
}
