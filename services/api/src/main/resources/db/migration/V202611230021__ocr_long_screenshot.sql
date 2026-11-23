-- 长截图分段 OCR（指导书 5.1 / B-01）：保留段序、段索引与纵向偏移，便于回到原图定位。
-- 段文本为各段独立 OCR 的原始结果；合并后的全文仍落在 ocr_results.text。
ALTER TABLE ocr_results ADD COLUMN image_width  integer;
ALTER TABLE ocr_results ADD COLUMN image_height integer;
ALTER TABLE ocr_results ADD COLUMN segment_count integer NOT NULL DEFAULT 1;

CREATE TABLE ocr_segments (
    id            uuid PRIMARY KEY,
    result_id     uuid NOT NULL REFERENCES ocr_results(id) ON DELETE CASCADE,
    segment_index integer NOT NULL,
    y_offset      integer NOT NULL,
    height        integer NOT NULL,
    text          text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uniq_ocr_segment_order UNIQUE (result_id, segment_index),
    CONSTRAINT ck_ocr_segment_bounds CHECK (segment_index >= 0 AND y_offset >= 0 AND height > 0)
);
CREATE INDEX idx_ocr_segments_result ON ocr_segments (result_id, segment_index);

-- 长图 OCR 的分段进度（仅用于展示“第 x/y 段”，不承载任何输入内容）。
ALTER TABLE jobs ADD COLUMN progress_current integer;
ALTER TABLE jobs ADD COLUMN progress_total   integer;
