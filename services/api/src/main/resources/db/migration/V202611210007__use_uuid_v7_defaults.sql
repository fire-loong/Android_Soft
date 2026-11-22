-- 对齐 ADR-001「主键 UUIDv7」契约：用 PL/pgSQL 生成 v7（时间有序，利于 B-tree），
-- 并将 V1–V5 中所有主键列的默认值从 gen_random_uuid()(v4) 切换为 uuid_v7()。
-- 应用侧仍通过 UuidV7 生成器（@PrePersist）显式写入，DB 默认仅作为原始 SQL 插入的兜底。
-- 依赖：仅用 core 的 random()/to_hex/clock_timestamp()，不依赖 pgcrypto 或 pg_uuidv7 扩展。

CREATE OR REPLACE FUNCTION uuid_v7() RETURNS uuid
LANGUAGE plpgsql
AS $$
DECLARE
  ts_ms  bigint := (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint;
  rand_a bigint := (random() * 4096)::bigint;                          -- 12 bits
  rand_b bigint := (random() * 4611686018427387904)::bigint;           -- 62 bits (0 .. 2^62-1)
  var_hi int    := 8 + ((rand_b >> 60) & 3);                           -- 变体 10xx + rand_b 高 2 位
BEGIN
  RETURN (
    lpad(to_hex(ts_ms & 281474976710655), 12, '0')                     -- 48 位时间戳（毫秒）
    || '7'                                                             -- version = 0111
    || lpad(to_hex(rand_a & 4095), 3, '0')                             -- rand_a（12 位）
    || to_hex(var_hi)                                                  -- 变体 nibble（8..b）
    || lpad(to_hex(rand_b & 1152921504606846975), 15, '0')             -- rand_b 低 60 位
  )::uuid;
END $$;

-- 将所有以 gen_random_uuid() 为默认值的列（当前全部为主键）切换到 uuid_v7()
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN
    SELECT table_schema, table_name, column_name
    FROM information_schema.columns
    WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
      AND column_default LIKE '%gen_random_uuid()%'
  LOOP
    EXECUTE format('ALTER TABLE %I.%I ALTER COLUMN %I SET DEFAULT uuid_v7()',
                   r.table_schema, r.table_name, r.column_name);
  END LOOP;
END $$;
