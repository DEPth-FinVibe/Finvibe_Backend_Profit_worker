package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import depth.finvibe.profit.worker.application.port.out.UserStateStore;
import depth.finvibe.profit.worker.application.port.out.ValuationRepository;
import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;
import depth.finvibe.profit.worker.dto.ProfitCalculationDto;
import depth.finvibe.profit.worker.support.TestMetricsFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ProfitCalculateServiceTest {

    @Test
    void updatesPortfolioAndUserValuationByStockPriceChange() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        FakeUserStateStore userStateStore = new FakeUserStateStore(valuationRepository);
        ProfitCalculateService service = new ProfitCalculateService(
                portfolioStateStore,
                userStateStore,
                valuationRepository,
                fixture.metrics(),
                Runnable::run
        );

        service.updateProfitByStockPriceChange(ProfitCalculationDto.ProfitCalculationRequest.builder()
                .stockId(10L)
                .newPrice(150L)
                .build());

        PortfolioValuation firstPortfolio = valuationRepository.portfolioValuations.get(1L);
        assertThat(firstPortfolio.getPurchasedValue()).isEqualTo(1_000L);
        assertThat(firstPortfolio.getCurrentValue()).isEqualTo(1_500L);
        assertThat(firstPortfolio.getProfitRate()).isEqualTo(50.0);
        assertThat(firstPortfolio.getAssetCount()).isEqualTo(2L);

        PortfolioValuation secondPortfolio = valuationRepository.portfolioValuations.get(2L);
        assertThat(secondPortfolio.getPurchasedValue()).isEqualTo(1_000L);
        assertThat(secondPortfolio.getCurrentValue()).isEqualTo(700L);
        assertThat(secondPortfolio.getProfitRate()).isEqualTo(-30.0);
        assertThat(secondPortfolio.getAssetCount()).isEqualTo(1L);

        assertThat(valuationRepository.savedUserValuations).hasSize(1);

        UserValuation userValuation = valuationRepository.userValuations.get("100");
        assertThat(userValuation.getPurchasedValue()).isEqualTo(2_000L);
        assertThat(userValuation.getCurrentValue()).isEqualTo(2_200L);
        assertThat(userValuation.getProfitRate()).isEqualTo(10.0);
        assertThat(userValuation.getPortfolioCount()).isEqualTo(2L);
        assertThat(registry.find(ProfitWorkerMetrics.AFFECTED_PORTFOLIOS)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION)
                .summary().totalAmount()).isEqualTo(2.0);
        assertThat(registry.find(ProfitWorkerMetrics.AFFECTED_USERS)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION)
                .summary().totalAmount()).isEqualTo(1.0);
    }

    @Test
    void roundsFractionalPortfolioAndUserCurrentValue() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        portfolioStateStore.currentValues.put(1L, new BigDecimal("500.5"));
        portfolioStateStore.portfolioIdsByStockId.put(10L, List.of(1L));
        FakeUserStateStore userStateStore = new FakeUserStateStore(valuationRepository);
        userStateStore.userIdsByPortfolioId.put(1L, "100");
        userStateStore.portfolioIdsByUserId.put("100", List.of(1L));
        ProfitCalculateService service = new ProfitCalculateService(
                portfolioStateStore,
                userStateStore,
                valuationRepository,
                fixture.metrics(),
                Runnable::run
        );

        service.updateProfitByStockPriceChange(ProfitCalculationDto.ProfitCalculationRequest.builder()
                .stockId(10L)
                .newPrice(150L)
                .build());

        assertThat(valuationRepository.portfolioValuations.get(1L).getCurrentValue()).isEqualTo(501L);
        assertThat(valuationRepository.userValuations.get("100").getCurrentValue()).isEqualTo(501L);
    }

    @Test
    void usesZeroProfitRateWhenPurchasedValueIsZero() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        portfolioStateStore.purchasedValues.put(1L, 0L);
        portfolioStateStore.currentValues.put(1L, new BigDecimal("500"));

        FakeUserStateStore userStateStore = new FakeUserStateStore(valuationRepository);
        userStateStore.purchasedValues.put("100", 0L);
        ProfitCalculateService service = new ProfitCalculateService(
                portfolioStateStore,
                userStateStore,
                valuationRepository,
                fixture.metrics(),
                Runnable::run
        );

        service.updateProfitByStockPriceChange(ProfitCalculationDto.ProfitCalculationRequest.builder()
                .stockId(10L)
                .newPrice(150L)
                .build());

        assertThat(valuationRepository.portfolioValuations.get(1L).getProfitRate()).isEqualTo(0.0);
        assertThat(valuationRepository.userValuations.get("100").getProfitRate()).isEqualTo(0.0);
    }

    @Test
    void recordsZeroWorkloadMetricsWhenNoPortfolioIsAffected() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        portfolioStateStore.portfolioIdsByStockId.put(999L, List.of());
        FakeUserStateStore userStateStore = new FakeUserStateStore(valuationRepository);
        ProfitCalculateService service = new ProfitCalculateService(
                portfolioStateStore,
                userStateStore,
                valuationRepository,
                fixture.metrics(),
                Runnable::run
        );

        service.updateProfitByStockPriceChange(ProfitCalculationDto.ProfitCalculationRequest.builder()
                .stockId(999L)
                .newPrice(150L)
                .build());

        assertThat(registry.find(ProfitWorkerMetrics.AFFECTED_PORTFOLIOS)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION)
                .summary().count()).isEqualTo(1);
        assertThat(registry.find(ProfitWorkerMetrics.AFFECTED_PORTFOLIOS)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION)
                .summary().totalAmount()).isEqualTo(0.0);
        assertThat(registry.find(ProfitWorkerMetrics.AFFECTED_USERS)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION)
                .summary().count()).isEqualTo(1);
        assertThat(registry.find(ProfitWorkerMetrics.AFFECTED_USERS)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION)
                .summary().totalAmount()).isEqualTo(0.0);
    }

    private static class FakePortfolioStateStore implements PortfolioStateStore {

        private final Map<Long, List<Long>> portfolioIdsByStockId = new HashMap<>(Map.of(10L, List.of(1L, 2L)));
        private final Map<Long, Long> purchasedValues = new HashMap<>(Map.of(1L, 1_000L, 2L, 1_000L));
        private final Map<Long, BigDecimal> currentValues = new HashMap<>(Map.of(1L, new BigDecimal("1500"), 2L, new BigDecimal("700")));
        private final Map<Long, Long> assetCounts = Map.of(1L, 2L, 2L, 1L);

        @Override
        public List<Long> findPortfolioIdsByStockId(Long stockId) {
            return portfolioIdsByStockId.getOrDefault(stockId, List.of());
        }

        @Override
        public Long findPurchasedValue(Long portfolioId) {
            return purchasedValues.get(portfolioId);
        }

        @Override
        public BigDecimal findCurrentValue(Long portfolioId) {
            return currentValues.get(portfolioId);
        }

        @Override
        public BigDecimal calculateCurrentValue(Long portfolioId, Long changedStockId, Long newPrice) {
            return currentValues.get(portfolioId);
        }

        @Override
        public PortfolioCurrentValueUpdate recalculateCurrentValue(Long portfolioId, Long changedStockId, Long newPrice) {
            BigDecimal currentValue = currentValues.get(portfolioId);
            return new PortfolioCurrentValueUpdate(currentValue, currentValue, currentValue);
        }

        @Override
        public Long findAssetCount(Long portfolioId) {
            return assetCounts.get(portfolioId);
        }

        @Override
        public boolean increaseStockQuantity(Long stockId, Long portfolioId, BigDecimal quantity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean decreaseStockQuantity(Long stockId, Long portfolioId, BigDecimal quantity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addPurchasedValue(Long portfolioId, Long amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subtractPurchasedValue(Long portfolioId, Long amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addCurrentValue(Long portfolioId, BigDecimal amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subtractCurrentValue(Long portfolioId, BigDecimal amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addStockCurrentValue(Long stockId, Long portfolioId, BigDecimal amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subtractStockCurrentValue(Long stockId, Long portfolioId, BigDecimal amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void increaseAssetCount(Long portfolioId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void decreaseAssetCount(Long portfolioId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deletePortfolioState(Long portfolioId) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FakeUserStateStore implements UserStateStore {

        private final FakeValuationRepository valuationRepository;
        private final Map<Long, String> userIdsByPortfolioId = new HashMap<>(Map.of(1L, "100", 2L, "100"));
        private final Map<String, List<Long>> portfolioIdsByUserId = new HashMap<>(Map.of("100", List.of(1L, 2L)));
        private final Map<String, Long> purchasedValues = new HashMap<>(Map.of("100", 2_000L));
        private final Map<String, Long> portfolioCounts = Map.of("100", 2L);
        private final Map<String, BigDecimal> currentValues = new HashMap<>(Map.of("100", new BigDecimal("0")));

        private FakeUserStateStore(FakeValuationRepository valuationRepository) {
            this.valuationRepository = valuationRepository;
        }

        @Override
        public String findUserIdByPortfolioId(Long portfolioId) {
            return userIdsByPortfolioId.get(portfolioId);
        }

        @Override
        public Long findPurchasedValue(String userId) {
            return purchasedValues.get(userId);
        }

        @Override
        public BigDecimal calculateCurrentValue(String userId) {
            return portfolioIdsByUserId.get(userId).stream()
                    .map(valuationRepository.portfolioValuations::get)
                    .map(PortfolioValuation::getCurrentValue)
                    .map(BigDecimal::valueOf)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        @Override
        public BigDecimal findCurrentValue(String userId) {
            return currentValues.getOrDefault(userId, BigDecimal.ZERO);
        }

        @Override
        public BigDecimal addCurrentValue(String userId, BigDecimal delta) {
            BigDecimal nextValue = currentValues.getOrDefault(userId, BigDecimal.ZERO).add(delta);
            currentValues.put(userId, nextValue);
            return nextValue;
        }

        @Override
        public Long findPortfolioCount(String userId) {
            return portfolioCounts.get(userId);
        }

        @Override
        public void mapPortfolioToUser(Long portfolioId, String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removePortfolioUserMapping(Long portfolioId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addPurchasedValue(String userId, Long amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subtractPurchasedValue(String userId, Long amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void increasePortfolioCount(String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void decreasePortfolioCount(String userId) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FakeValuationRepository implements ValuationRepository {

        private final Map<Long, PortfolioValuation> portfolioValuations = new ConcurrentHashMap<>();
        private final Map<String, UserValuation> userValuations = new ConcurrentHashMap<>();
        private final List<UserValuation> savedUserValuations = new ArrayList<>();

        @Override
        public void savePortfolioValuation(PortfolioValuation valuation) {
            portfolioValuations.put(valuation.getPortfolioId(), valuation);
        }

        @Override
        public void markPortfolioValuationDeleted(Long portfolioId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveUserValuation(UserValuation valuation) {
            userValuations.put(valuation.getUserId(), valuation);
            savedUserValuations.add(valuation);
        }
    }
}
