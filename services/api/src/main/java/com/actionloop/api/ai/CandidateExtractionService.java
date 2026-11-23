package com.actionloop.api.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 候选抽取编排：生产路径走真实 Provider；失败显式降级本地规则。
 * 成功结果按 inputHash + 模型/Prompt/Schema 版本缓存，缓存仍受 workspace、用户和来源 ACL 约束。
 */
@Service
public class CandidateExtractionService {

    private static final Logger log = LoggerFactory.getLogger(CandidateExtractionService.class);

    private final List<AiProvider> providers;
    private final AiProperties props;
    private final RuleExtractor ruleExtractor;
    private final AiResultCacheService cacheService;

    public CandidateExtractionService(List<AiProvider> providers, AiProperties props,
                                      RuleExtractor ruleExtractor, AiResultCacheService cacheService) {
        this.providers = providers;
        this.props = props;
        this.ruleExtractor = ruleExtractor;
        this.cacheService = cacheService;
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
            warnings.add("使用确定性测试 Provider（非 AI），真实抽取请配置 ACTIONLOOP_AI_PROVIDER=maas");
            return new CandidateExtraction(rule.candidates(), rule.modelVersion(), rule.promptVersion(),
                    rule.schemaVersion(), "RULE", warnings);
        }
        Optional<CandidateExtraction> cached = cacheService.find(request, props.getModel(),
                QwenAiProvider.PROMPT_VERSION, QwenAiProvider.SCHEMA_VERSION);
        if (cached.isPresent()) {
            CandidateExtraction extraction = asAi(cached.get());
            List<String> warnings = new ArrayList<>(extraction.warnings());
            warnings.add("AI_CACHE_HIT: 命中同用户同来源的派生结果缓存");
            return new CandidateExtraction(extraction.candidates(), extraction.modelVersion(),
                    extraction.promptVersion(), extraction.schemaVersion(), "AI", warnings);
        }
        try {
            CandidateExtraction extraction = asAi(provider.extract(request));
            cacheService.store(request, extraction);
            return extraction;
        } catch (com.actionloop.shared.error.BusinessException e) {
            if (e.code() != com.actionloop.shared.error.ErrorCode.AI_UNAVAILABLE) {
                throw e;
            }
            log.warn("AI extraction unavailable; using explicit rule fallback");
            CandidateExtraction rule = ruleExtractor.extract(request);
            List<String> warnings = new ArrayList<>(rule.warnings());
            warnings.add("AI_UNAVAILABLE_FALLBACK: 真实模型暂不可用，本次使用本地规则");
            return new CandidateExtraction(rule.candidates(), rule.modelVersion(), rule.promptVersion(),
                    rule.schemaVersion(), "RULE", warnings);
        }
    }

    private static CandidateExtraction asAi(CandidateExtraction value) {
        List<String> warnings = new ArrayList<>(value.warnings());
        if (!warnings.contains("AI 结果仅作候选，需用户确认后才创建事项与提醒")) {
            warnings.add("AI 结果仅作候选，需用户确认后才创建事项与提醒");
        }
        return new CandidateExtraction(value.candidates(), value.modelVersion(), value.promptVersion(),
                value.schemaVersion(), "AI", warnings);
    }
}
