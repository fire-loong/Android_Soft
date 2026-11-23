package com.actionloop.api.ai;

import com.actionloop.shared.error.BusinessException;
import com.actionloop.shared.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CandidateExtractionServiceTest {

    @Test
    void secondIdenticalRequestUsesPrivateCacheInsteadOfCallingProvider() {
        AiProperties props = new AiProperties();
        props.setProvider("maas");
        props.setModel("spark-x2.6");
        InMemoryCache cache = new InMemoryCache();
        FakeProvider provider = new FakeProvider(false);
        CandidateExtractionService service = new CandidateExtractionService(
                List.of(provider), props, new RuleExtractor(), cache);
        ExtractionRequest request = request();

        CandidateExtraction first = service.extract(request);
        CandidateExtraction second = service.extract(request);

        assertEquals(1, provider.calls.get());
        assertEquals(1, cache.stores.get());
        assertEquals("AI", first.derivation());
        assertTrue(second.warnings().stream().anyMatch(w -> w.startsWith("AI_CACHE_HIT")));
    }

    @Test
    void providerFailureIsExplicitlyMarkedAsRuleFallback() {
        AiProperties props = new AiProperties();
        props.setProvider("maas");
        InMemoryCache cache = new InMemoryCache();
        FakeProvider provider = new FakeProvider(true);
        CandidateExtractionService service = new CandidateExtractionService(
                List.of(provider), props, new RuleExtractor(), cache);

        CandidateExtraction result = service.extract(request());

        assertEquals("RULE", result.derivation());
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("AI_UNAVAILABLE_FALLBACK")));
        assertEquals(0, cache.stores.get(), "规则降级结果不得冒充 AI 写入模型缓存");
    }

    @Test
    void invalidAiOutputIsNotMisreportedAsUnavailableOrReplacedByRules() {
        AiProperties props = new AiProperties();
        props.setProvider("maas");
        InMemoryCache cache = new InMemoryCache();
        FakeProvider provider = new FakeProvider(true);
        provider.failureCode = ErrorCode.AI_OUTPUT_INVALID;
        CandidateExtractionService service = new CandidateExtractionService(
                List.of(provider), props, new RuleExtractor(), cache);

        BusinessException error = assertThrows(BusinessException.class, () -> service.extract(request()));

        assertEquals(ErrorCode.AI_OUTPUT_INVALID, error.code());
        assertEquals(0, cache.stores.get());
    }

    @Test
    void deterministicSelectionNeverCallsProductionProvider() {
        AiProperties props = new AiProperties();
        props.setProvider("deterministic");
        RuleExtractor rules = new RuleExtractor();
        FakeProvider production = new FakeProvider(false);
        CandidateExtractionService service = new CandidateExtractionService(
                List.of(production, new DeterministicAiProvider(rules)), props, rules, new InMemoryCache());

        CandidateExtraction result = service.extract(request());

        assertEquals(0, production.calls.get());
        assertEquals("RULE", result.derivation());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("确定性测试 Provider")));
    }

    private static ExtractionRequest request() {
        return new ExtractionRequest("请于11月30日18:00前提交课程设计PDF。",
                Instant.parse("2026-11-22T02:00:00Z"), "Asia/Shanghai", "班级群", "通知",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-cache");
    }

    private static final class FakeProvider implements AiProvider {
        private final boolean fail;
        private ErrorCode failureCode = ErrorCode.AI_UNAVAILABLE;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeProvider(boolean fail) {
            this.fail = fail;
        }

        @Override
        public String name() {
            return "maas";
        }

        @Override
        public boolean production() {
            return true;
        }

        @Override
        public CandidateExtraction extract(ExtractionRequest request) {
            calls.incrementAndGet();
            if (fail) {
                throw new BusinessException(failureCode, "test failure");
            }
            CandidateExtraction rule = new RuleExtractor().extract(request);
            return new CandidateExtraction(rule.candidates(), "spark-x2.6", QwenAiProvider.PROMPT_VERSION,
                    QwenAiProvider.SCHEMA_VERSION, "AI", List.of());
        }

        @Override
        public CandidateExtraction generateStructured(String schemaVersion, ExtractionRequest input,
                                                      Duration timeout, String traceId) {
            return extract(input);
        }

        @Override
        public boolean health() {
            return !fail;
        }
    }

    private static final class InMemoryCache extends AiResultCacheService {
        private CandidateExtraction value;
        private final AtomicInteger stores = new AtomicInteger();

        private InMemoryCache() {
            super((JdbcTemplate) null, (ObjectMapper) null,
                    org.mockito.Mockito.mock(PlatformTransactionManager.class));
        }

        @Override
        public Optional<CandidateExtraction> find(ExtractionRequest request, String modelVersion,
                                                  String promptVersion, String schemaVersion) {
            return Optional.ofNullable(value);
        }

        @Override
        public void store(ExtractionRequest request, CandidateExtraction extraction) {
            stores.incrementAndGet();
            value = extraction;
        }
    }
}
