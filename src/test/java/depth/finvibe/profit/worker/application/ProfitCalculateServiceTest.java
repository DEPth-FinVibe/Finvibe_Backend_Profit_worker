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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ProfitCalculateServiceTest {

    @Test
    void updatesPortfolioAndUserValuationByStockPriceChange() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        FakeUserStateStore userStateStore = new FakeUserStateStore();
        ProfitCalculateService service = new ProfitCalculateService(
                portfolioStateStore,
                userStateStore,
                valuationRepository,
                fixture.metrics()
        );

        // Portfolio 1: holds 10 shares of stock 10, oldStockCV=1000, portfolioCV=1000
        // Portfolio 2: holds 2 shares of stock 10, oldStockCV=600, portfolioCV=1000
        portfolioStateStore.portfolioIdsByStockId.put(10L, List.of(1L, 2L));
        portfolioStateStore.portfolioMetadata.put(1L, new PortfolioStateStore.PortfolioMetadata(1_000L, 2L, "100", new BigDecimal("1000")));
        portfolioStateStore.portfolioMetadata.put(2L, new PortfolioStateStore.PortfolioMetadata(1_000L, 1L, "100", new BigDecimal("1000")));
        portfolioStateStore.stockHoldings.put("1:10", new PortfolioStateStore.StockHolding(BigDecimal.TEN, new BigDecimal("1000")));
        portfolioStateStore.stockHoldings.put("2:10", new PortfolioStateStore.StockHolding(new BigDecimal("2"), new BigDecimal("600")));

        userStateStore.userMetadata.put("100", new UserStateStore.UserMetadata(2_000L, 2L));
        userStateStore.currentValues.put("100", new BigDecimal("2000"));

        // Stock 10 price changes to 150
        // Portfolio 1: newStockCV=1500, delta=+500, newCV=1500
        // Portfolio 2: newStockCV=300, delta=-300, newCV=700
        // User "100": delta=+200, newCV=2200
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
        FakeUserStateStore userStateStore = new FakeUserStateStore();
        ProfitCalculateService service = new ProfitCalculateService(
                portfolioStateStore,
                userStateStore,
                valuationRepository,
                fixture.metrics()
        );

        // Portfolio 1: oldCV=400.5, stock 10 quantity=10, oldStockCV=1400
        // After price→150: newStockCV=1500, delta=100, newCV=500.5 → rounds to 501
        portfolioStateStore.portfolioIdsByStockId.put(10L, List.of(1L));
        portfolioStateStore.portfolioMetadata.put(1L, new PortfolioStateStore.PortfolioMetadata(1_000L, 2L, "100", new BigDecimal("400.5")));
        portfolioStateStore.stockHoldings.put("1:10", new PortfolioStateStore.StockHolding(BigDecimal.TEN, new BigDecimal("1400")));

        userStateStore.userMetadata.put("100", new UserStateStore.UserMetadata(1_000L, 1L));
        userStateStore.currentValues.put("100", new BigDecimal("400.5"));

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
        FakeUserStateStore userStateStore = new FakeUserStateStore();
        ProfitCalculateService service = new ProfitCalculateService(
                portfolioStateStore,
                userStateStore,
                valuationRepository,
                fixture.metrics()
        );

        portfolioStateStore.portfolioIdsByStockId.put(10L, List.of(1L));
        portfolioStateStore.portfolioMetadata.put(1L, new PortfolioStateStore.PortfolioMetadata(0L, 1L, "100", new BigDecimal("500")));
        portfolioStateStore.stockHoldings.put("1:10", new PortfolioStateStore.StockHolding(BigDecimal.TEN, new BigDecimal("1000")));

        userStateStore.userMetadata.put("100", new UserStateStore.UserMetadata(0L, 1L));
        userStateStore.currentValues.put("100", new BigDecimal("500"));

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
        FakeUserStateStore userStateStore = new FakeUserStateStore();
        ProfitCalculateService service = new ProfitCalculateService(
                portfolioStateStore,
                userStateStore,
                valuationRepository,
                fixture.metrics()
        );

        portfolioStateStore.portfolioIdsByStockId.put(999L, List.of());

        service.updateProfitByStockPriceChange(ProfitCalculationDto.ProfitCalculationRequest.builder()
                .stockId(999L)
                .newPrice(150L)
                .build());

        assertThat(valuationRepository.portfolioValuations).isEmpty();
        assertThat(valuationRepository.userValuations).isEmpty();
    }

    private static class FakePortfolioStateStore implements PortfolioStateStore {

        final Map<Long, List<Long>> portfolioIdsByStockId = new HashMap<>();
        final Map<Long, PortfolioMetadata> portfolioMetadata = new HashMap<>();
        final Map<String, StockHolding> stockHoldings = new HashMap<>();
        final Map<Long, BigDecimal> currentValues = new HashMap<>();

        @Override
        public List<Long> findPortfolioIdsByStockId(Long stockId) {
            return portfolioIdsByStockId.getOrDefault(stockId, List.of());
        }

        @Override
        public Long findPurchasedValue(Long portfolioId) {
            PortfolioMetadata meta = portfolioMetadata.get(portfolioId);
            return meta != null ? meta.purchasedValue() : 0L;
        }

        @Override
        public BigDecimal findCurrentValue(Long portfolioId) {
            return currentValues.getOrDefault(portfolioId, BigDecimal.ZERO);
        }

        @Override
        public BigDecimal calculateCurrentValue(Long portfolioId, Long changedStockId, Long newPrice) {
            return findCurrentValue(portfolioId);
        }

        @Override
        public PortfolioCurrentValueUpdate recalculateCurrentValue(Long portfolioId, Long changedStockId, Long newPrice) {
            BigDecimal cv = findCurrentValue(portfolioId);
            return new PortfolioCurrentValueUpdate(cv, cv, BigDecimal.ZERO);
        }

        @Override
        public Long findAssetCount(Long portfolioId) {
            PortfolioMetadata meta = portfolioMetadata.get(portfolioId);
            return meta != null ? meta.assetCount() : 0L;
        }

        @Override
        public Map<Long, PortfolioMetadata> bulkFetchPortfolioMetadata(List<Long> portfolioIds) {
            Map<Long, PortfolioMetadata> result = new HashMap<>();
            for (Long id : portfolioIds) {
                PortfolioMetadata meta = portfolioMetadata.get(id);
                if (meta != null) {
                    result.put(id, meta);
                }
            }
            return result;
        }

        @Override
        public Map<String, StockHolding> bulkFetchStockHoldings(List<StockHoldingKey> tasks) {
            Map<String, StockHolding> result = new HashMap<>();
            for (StockHoldingKey key : tasks) {
                StockHolding holding = stockHoldings.get(key.toKey());
                if (holding != null) {
                    result.put(key.toKey(), holding);
                }
            }
            return result;
        }

        @Override
        public Map<Long, BigDecimal> bulkIncrementCurrentValues(Map<Long, BigDecimal> deltasByPortfolioId) {
            Map<Long, BigDecimal> result = new HashMap<>();
            for (var entry : deltasByPortfolioId.entrySet()) {
                Long portfolioId = entry.getKey();
                BigDecimal delta = entry.getValue();
                PortfolioMetadata meta = portfolioMetadata.get(portfolioId);
                BigDecimal oldCV = meta != null ? meta.currentValue() : BigDecimal.ZERO;
                BigDecimal newCV = oldCV.add(delta);
                currentValues.put(portfolioId, newCV);
                result.put(portfolioId, newCV);
            }
            return result;
        }

        @Override
        public Map<Long, List<Long>> bulkFindPortfolioIdsByStockIds(List<Long> stockIds) {
            Map<Long, List<Long>> result = new HashMap<>();
            for (Long stockId : stockIds) {
                result.put(stockId, portfolioIdsByStockId.getOrDefault(stockId, List.of()));
            }
            return result;
        }

        @Override
        public String stockCurrentValueKey(Long portfolioId, Long stockId) {
            return "portfolio:" + portfolioId + ":stock:" + stockId + ":current-value";
        }

        @Override
        public void bulkSetStockCurrentValues(Map<String, BigDecimal> updates) {
            // no-op for tests
        }

        @Override public boolean increaseStockQuantity(Long stockId, Long portfolioId, BigDecimal quantity) { throw new UnsupportedOperationException(); }
        @Override public boolean decreaseStockQuantity(Long stockId, Long portfolioId, BigDecimal quantity) { throw new UnsupportedOperationException(); }
        @Override public void addPurchasedValue(Long portfolioId, Long amount) { throw new UnsupportedOperationException(); }
        @Override public void subtractPurchasedValue(Long portfolioId, Long amount) { throw new UnsupportedOperationException(); }
        @Override public void addCurrentValue(Long portfolioId, BigDecimal amount) { throw new UnsupportedOperationException(); }
        @Override public void subtractCurrentValue(Long portfolioId, BigDecimal amount) { throw new UnsupportedOperationException(); }
        @Override public void addStockCurrentValue(Long stockId, Long portfolioId, BigDecimal amount) { throw new UnsupportedOperationException(); }
        @Override public void subtractStockCurrentValue(Long stockId, Long portfolioId, BigDecimal amount) { throw new UnsupportedOperationException(); }
        @Override public void increaseAssetCount(Long portfolioId) { throw new UnsupportedOperationException(); }
        @Override public void decreaseAssetCount(Long portfolioId) { throw new UnsupportedOperationException(); }
        @Override public void deletePortfolioState(Long portfolioId) { throw new UnsupportedOperationException(); }
    }

    private static class FakeUserStateStore implements UserStateStore {

        final Map<String, UserMetadata> userMetadata = new HashMap<>();
        final Map<String, BigDecimal> currentValues = new HashMap<>();

        @Override
        public String findUserIdByPortfolioId(Long portfolioId) { throw new UnsupportedOperationException(); }

        @Override
        public Long findPurchasedValue(String userId) {
            UserMetadata meta = userMetadata.get(userId);
            return meta != null ? meta.purchasedValue() : 0L;
        }

        @Override
        public BigDecimal calculateCurrentValue(String userId) {
            return currentValues.getOrDefault(userId, BigDecimal.ZERO);
        }

        @Override
        public BigDecimal findCurrentValue(String userId) {
            return currentValues.getOrDefault(userId, BigDecimal.ZERO);
        }

        @Override
        public BigDecimal addCurrentValue(String userId, BigDecimal delta) {
            BigDecimal newValue = currentValues.getOrDefault(userId, BigDecimal.ZERO).add(delta);
            currentValues.put(userId, newValue);
            return newValue;
        }

        @Override
        public Long findPortfolioCount(String userId) {
            UserMetadata meta = userMetadata.get(userId);
            return meta != null ? meta.portfolioCount() : 0L;
        }

        @Override
        public Map<String, UserMetadata> bulkFetchUserMetadata(List<String> userIds) {
            Map<String, UserMetadata> result = new HashMap<>();
            for (String id : userIds) {
                UserMetadata meta = userMetadata.get(id);
                if (meta != null) {
                    result.put(id, meta);
                }
            }
            return result;
        }

        @Override
        public Map<String, BigDecimal> bulkIncrementCurrentValues(Map<String, BigDecimal> deltasByUserId) {
            Map<String, BigDecimal> result = new HashMap<>();
            for (var entry : deltasByUserId.entrySet()) {
                BigDecimal newValue = currentValues.getOrDefault(entry.getKey(), BigDecimal.ZERO).add(entry.getValue());
                currentValues.put(entry.getKey(), newValue);
                result.put(entry.getKey(), newValue);
            }
            return result;
        }

        @Override public void mapPortfolioToUser(Long portfolioId, String userId) { throw new UnsupportedOperationException(); }
        @Override public void removePortfolioUserMapping(Long portfolioId) { throw new UnsupportedOperationException(); }
        @Override public void addPurchasedValue(String userId, Long amount) { throw new UnsupportedOperationException(); }
        @Override public void subtractPurchasedValue(String userId, Long amount) { throw new UnsupportedOperationException(); }
        @Override public void increasePortfolioCount(String userId) { throw new UnsupportedOperationException(); }
        @Override public void decreasePortfolioCount(String userId) { throw new UnsupportedOperationException(); }
    }

    private static class FakeValuationRepository implements ValuationRepository {

        final Map<Long, PortfolioValuation> portfolioValuations = new ConcurrentHashMap<>();
        final Map<String, UserValuation> userValuations = new ConcurrentHashMap<>();
        final List<UserValuation> savedUserValuations = new ArrayList<>();

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

        @Override
        public void bulkSavePortfolioValuations(List<PortfolioValuation> valuations) {
            for (PortfolioValuation v : valuations) {
                portfolioValuations.put(v.getPortfolioId(), v);
            }
        }

        @Override
        public void bulkSaveUserValuations(List<UserValuation> valuations) {
            for (UserValuation v : valuations) {
                userValuations.put(v.getUserId(), v);
                savedUserValuations.add(v);
            }
        }
    }
}
