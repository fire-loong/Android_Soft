package com.actionloop.shared.domain;

import java.util.UUID;

/**
 * UUIDv7 生成器（RFC 9562 风格）：48 位毫秒时间戳 + 版本位 + 74 位单调计数器。
 * <p>同毫秒内计数器递增，保证生成顺序与自然排序一致，利于 B-tree 索引局部性；
 * 系统时钟回拨时冻结在 lastMs，避免主键回退。客户端离线可生成。
 * 与指导书 3.2 一致：主键使用 UUIDv7。
 */
public final class UuidV7 {

    private static final long VERSION = 0x7L << 12;      // version 7
    private static final long VARIANT = 0x2L << 62;      // RFC 4122 variant 10xxxxxx
    private static final long MASK_48 = 0x0000_FFFF_FFFF_FFFFL;
    private static final long MASK_12 = 0x0FFFL;
    private static final long MASK_62 = 0x3FFF_FFFF_FFFF_FFFFL;

    private static final Object LOCK = new Object();
    private static long lastMs = -1L;
    private static long seq = 0L;

    private UuidV7() {
    }

    public static UUID generate() {
        return generate(System.currentTimeMillis());
    }

    public static UUID generate(long epochMillis) {
        long ms = epochMillis & MASK_48;
        long counter;
        synchronized (LOCK) {
            if (ms <= lastMs) {
                ms = lastMs;           // 时钟回拨或同毫秒：冻结时间戳
                seq++;
            } else {
                lastMs = ms;
                seq = 0L;
            }
            counter = seq;
        }
        // high 64 bits: timestamp(48) | version(4) | counter 高 12 位
        long msb = (ms << 16) | VERSION | ((counter >>> 62) & MASK_12);
        // low 64 bits: variant(2) | counter 低 62 位
        long lsb = VARIANT | (counter & MASK_62);
        return new UUID(msb, lsb);
    }

    /** 校验是否为 v7（用于输入防御）。 */
    public static boolean isV7(UUID uuid) {
        return uuid != null && uuid.version() == 7;
    }
}
