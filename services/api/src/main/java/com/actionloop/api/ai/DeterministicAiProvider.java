package com.actionloop.api.ai;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 确定性测试 Provider：只使用本地正则规则，不联网、不声称是 AI。
 * 用于无模型密钥的本地开发与自动化测试；生产必须配置 app.ai.provider=qwen。
 */
@Component
public class DeterministicAiProvider implements AiProvider {

    public static final String NAME = "deterministic-test";
    public static final String MODEL_VERSION = "rule-cn-v1+deterministic-test";
    public static final String PROMPT_VERSION = "rule-v1";
    public static final String SCHEMA_VERSION = "notification-task-v1";

    private final RuleExtractor ruleExtractor;

    public DeterministicAiProvider(RuleExtractor ruleExtractor) {
        this.ruleExtractor = ruleExtractor;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean production() {
        return false;
    }

    @Override
    public CandidateExtraction extract(ExtractionRequest request) {
        CandidateExtraction rule = ruleExtractor.extract(request);
        return new CandidateExtraction(rule.candidates(), MODEL_VERSION, PROMPT_VERSION,
                SCHEMA_VERSION, "RULE", rule.warnings());
    }

    @Override
    public CandidateExtraction generateStructured(String schemaVersion, ExtractionRequest input,
                                                  Duration timeout, String traceId) {
        return extract(input);
    }

    @Override
    public boolean health() {
        return true;
    }
}
