package com.ato.account.util;

import com.ato.account.config.AtoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 雪花 ID 生成器（D3）。worker_id 由配置注入，含时钟回拨保护。
 * 结构：1 符号位 + 41 时间戳 + 10 workerId + 12 序列号。
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1735689600000L; // 2025-01-01T00:00:00Z
    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    /** 允许等待的最大回拨毫秒数，超过则拒绝生成（避免产出重复 ID） */
    private static final long MAX_BACKWARD_MS = 5L;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(AtoProperties properties) {
        long configured = properties.getSnowflake().getWorkerId();
        if (configured < 0 || configured > MAX_WORKER_ID) {
            throw new IllegalStateException("snowflake worker-id must be in [0, " + MAX_WORKER_ID + "], got " + configured);
        }
        this.workerId = configured;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset > MAX_BACKWARD_MS) {
                throw new IllegalStateException("clock moved backwards by " + offset + "ms, refusing to generate id");
            }
            log.warn("clock moved backwards by {}ms, waiting", offset);
            timestamp = waitUntil(lastTimestamp);
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT) | (workerId << WORKER_ID_SHIFT) | sequence;
    }

    private long waitUntil(long target) {
        long timestamp = System.currentTimeMillis();
        while (timestamp < target) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
