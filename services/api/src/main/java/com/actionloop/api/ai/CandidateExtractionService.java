package com.actionloop.api.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选抽取编排：生产路径走真实 Provider；任何失败都降级本地规则并标记原因。
 * AI 只产出候选，不触碰 Task 状态、权限、提醒（指导书 14.1）。
 */
@Service
public class CandidateExtractionService {

    private static final Logger log = LoggerFactory.getLogger(CandidateExtractionService.class);

    private final List<AiProvider> providers;
    private final AiProperties props;
    private final RuleExtractor ruleExtractor;

    public CandidateExtractionService(List<AiProvider> providers, AiProperties props, RuleExtractor ruleExtractor) {
        this.providers = providers;
        this.props = props;
        this.ruleExtractor = ruleExtractor;
    }

    public CandidateExtraction extract(ExtractionRequest request) {
        AiProvider provider = providers.stream()
                .filter(p -> p.name().equals(props.getProvider()))
                .findFirst()
                .orElseGet(() -> providers.stream()
                        .filter(p -> p.name().equals(DeterministicAiProvider.NAME))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("缺少候选抽取 Provider")));
        if (!provider.production()) {
            CandidateExtraction rule = ruleExtractor.extract(request);
            List<String> warnings = new ArrayList<>(rule.warnings());
            warnings.add("使用确定性测试 Provider（非 AI），生产环境请配置 ACTIONLOOP_AI_PROVIDER=qwen");
            return new CandidateExtraction(rule.candidates(), rule.modelVersion(), rule.promptVersion(),
                    "RULE", warnings);
        }
        try {
            CandidateExtraction extraction = provider.extract(request);
            List<String> warnings = new ArrayList<>(extraction.warnings());
            warnings.add("AI 结果仅作候选，需用户确认后才创建事项与提醒");
            return new CandidateExtraction(extraction.candidates(), extraction.modelVersion(),
                    extraction.promptVersion(), "AI", warnings);
        } catch (RuntimeException e) {
            log.warn("AI extraction fell back to rules: {}", e.getClass().getSimpleName());
            CandidateExtraction rule = ruleExtractor.extract(request);
            List<String> warnings = new ArrayList<>(rule.warnings());
            warnings.add("AI_UNAVAILABLE_FALLBACK: 已使用本地规则，未上传云端");
            return new CandidateExtraction(rule.candidates(), rule.modelVersion(), rule.promptVersion(),
                    "RULE", warnings);
        }
    }
}
