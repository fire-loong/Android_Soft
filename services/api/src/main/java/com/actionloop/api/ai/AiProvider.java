package com.actionloop.api.ai;

/** 候选抽取 Provider。production=false 的实现只能用于测试与离线降级，必须显式标记。 */
public interface AiProvider {

    String name();

    boolean production();

    CandidateExtraction extract(ExtractionRequest request);
}
