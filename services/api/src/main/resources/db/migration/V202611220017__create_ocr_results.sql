-- 显式授权的云端纯文本 OCR；不包含 bbox，也不替代离线 G-OCR-01。
ALTER TABLE evidence_assets ADD COLUMN ocr_failure_code text;

CREATE TABLE ocr_results (
    id                    uuid PRIMARY KEY,
    asset_id              uuid NOT NULL REFERENCES evidence_assets(id) ON DELETE CASCADE,
    provider              text NOT NULL,
    model                 text NOT NULL,
    prompt_version        text NOT NULL,
    input_sha256          char(64) NOT NULL,
    text                  text NOT NULL,
    coordinates_available boolean NOT NULL DEFAULT false,
    created_by            uuid NOT NULL REFERENCES users(id),
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uniq_ocr_result_model UNIQUE (asset_id, provider, model, prompt_version)
);
