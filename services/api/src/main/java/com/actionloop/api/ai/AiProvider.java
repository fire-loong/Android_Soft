package com.actionloop.api.ai;

import java.time.Duration;

/** 统一结构化模型 Provider；production=false 只能用于测试与明确标记的离线降级。 */
public interface AiProvider {

    String name();

    boolean production();

    CandidateExtraction extract(ExtractionRequest request);

    CandidateExtraction generateStructured(String schemaVersion, ExtractionRequest input,
                                           Duration timeout, String traceId);

    boolean health();
}
