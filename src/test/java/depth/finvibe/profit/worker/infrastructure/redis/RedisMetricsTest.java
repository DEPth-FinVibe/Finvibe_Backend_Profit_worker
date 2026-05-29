package depth.finvibe.profit.worker.infrastructure.redis;

import depth.finvibe.profit.worker.application.ProfitWorkerMetrics;
import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;
import depth.finvibe.profit.worker.support.TestMetricsFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisMetricsTest {

    @Test
    void recordsPortfolioCurrentValueMetric() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(hashOperations.get(anyString(), anyString())).thenReturn("100");

        RedisPortfolioStateStore store = new RedisPortfolioStateStore(redisTemplate, fixture.metrics());

        assertThat(store.calculateCurrentValue(1L, 10L, 200L)).isEqualTo(100L);
        assertThat(registry.find(ProfitWorkerMetrics.REDIS_OPERATION_DURATION)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_PORTFOLIO_CURRENT_VALUE,
                        ProfitWorkerMetrics.TAG_RESULT, ProfitWorkerMetrics.RESULT_SUCCESS)
                .timer().count()).isEqualTo(1);
    }

    @Test
    void recordsUserCurrentValueMetric() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        SetOperations<String, String> setOperations = mock(SetOperations.class);

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(anyString())).thenReturn(null);

        RedisUserStateStore store = new RedisUserStateStore(redisTemplate, fixture.metrics());

        assertThat(store.calculateCurrentValue("user-1")).isZero();
        assertThat(registry.find(ProfitWorkerMetrics.REDIS_OPERATION_DURATION)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_USER_CURRENT_VALUE,
                        ProfitWorkerMetrics.TAG_RESULT, ProfitWorkerMetrics.RESULT_SUCCESS)
                .timer().count()).isEqualTo(1);
    }

    @Test
    void recordsPortfolioValuationSaveMetric() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        SetOperations<String, String> setOperations = mock(SetOperations.class);

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        RedisValuationRepositoryAdapter repository = new RedisValuationRepositoryAdapter(redisTemplate, fixture.metrics());
        repository.savePortfolioValuation(PortfolioValuation.builder()
                .portfolioId(1L)
                .purchasedValue(100L)
                .currentValue(150L)
                .profitRate(50.0)
                .assetCount(2L)
                .build());

        assertThat(registry.find(ProfitWorkerMetrics.REDIS_OPERATION_DURATION)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_PORTFOLIO_VALUATION_SAVE,
                        ProfitWorkerMetrics.TAG_RESULT, ProfitWorkerMetrics.RESULT_SUCCESS)
                .timer().count()).isEqualTo(1);
    }

    @Test
    void recordsUserValuationSaveMetric() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        SetOperations<String, String> setOperations = mock(SetOperations.class);

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        RedisValuationRepositoryAdapter repository = new RedisValuationRepositoryAdapter(redisTemplate, fixture.metrics());
        repository.saveUserValuation(UserValuation.builder()
                .userId("user-1")
                .purchasedValue(100L)
                .currentValue(150L)
                .profitRate(50.0)
                .portfolioCount(2L)
                .build());

        assertThat(registry.find(ProfitWorkerMetrics.REDIS_OPERATION_DURATION)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_USER_VALUATION_SAVE,
                        ProfitWorkerMetrics.TAG_RESULT, ProfitWorkerMetrics.RESULT_SUCCESS)
                .timer().count()).isEqualTo(1);
    }

    @Test
    void recordsPortfolioValuationFailureMetric() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        doThrow(new IllegalStateException("boom")).when(hashOperations).putAll(anyString(), any(Map.class));

        RedisValuationRepositoryAdapter repository = new RedisValuationRepositoryAdapter(redisTemplate, fixture.metrics());

        assertThatThrownBy(() -> repository.savePortfolioValuation(PortfolioValuation.builder()
                .portfolioId(1L)
                .purchasedValue(100L)
                .currentValue(150L)
                .profitRate(50.0)
                .assetCount(2L)
                .build())).isInstanceOf(IllegalStateException.class);

        assertThat(registry.find(ProfitWorkerMetrics.REDIS_OPERATION_DURATION)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_PORTFOLIO_VALUATION_SAVE,
                        ProfitWorkerMetrics.TAG_RESULT, ProfitWorkerMetrics.RESULT_FAILURE)
                .timer().count()).isEqualTo(1);
    }
}
