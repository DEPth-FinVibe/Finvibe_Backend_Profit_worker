package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
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
    void updatesPortfolioValuationByStockPriceChange() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        ProfitCalculateService service = new ProfitCalculateService(
                portfolioStateStore,
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

        // Stock 10 price changes to 150
        // Portfolio 1: newStockCV=1500, delta=+500, newCV=1500
        // Portfolio 2: newStockCV=300, delta=-300, newCV=700
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

        assertThat(portfolioStateStore.bulkIncrementAndFetchCalls).isEqualTo(1);
        assertThat(portfolioStateStore.bulkIncrementCalls).isZero();
        assertThat(portfolioStateStore.bulkFetchMetadataCalls).isZero();
        assertThat(valuationRepository.bulkPortfolioPriceUpdateSaveCalls).isEqualTo(1);
        assertThat(valuationRepository.bulkPortfolioSaveCalls).isZero();
        assertThat(valuationRepository.bulkUserPriceUpdateSaveCalls).isZero();
        assertThat(registry.find(ProfitWorkerMetrics.AFFECTED_PORTFOLIOS)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION)
                .summary().totalAmount()).isEqualTo(2.0);
        assertThat(registry.find(ProfitWorkerMetrics.AFFECTED_USERS)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION)
                .summary()).isNull();
    }

    @Test
    void roundsFractionalPortfolioCurrentValue() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        ProfitCalculateService service = new ProfitCalculateService(
                portfolioStateStore,
                valuationRepository,
                fixture.metrics()
        );

        // Portfolio 1: oldCV=400.5, stock 10 quantity=10, oldStockCV=1400
        // After price→150: newStockCV=1500, delta=100, newCV=500.5 → rounds to 501
        portfolioStateStore.portfolioIdsByStockId.put(10L, List.of(1L));
        portfolioStateStore.portfolioMetadata.put(1L, new PortfolioStateStore.PortfolioMetadata(1_000L, 2L, "100", new BigDecimal("400.5")));
        portfolioStateStore.stockHoldings.put("1:10", new PortfolioStateStore.StockHolding(BigDecimal.TEN, new BigDecimal("1400")));

        service.updateProfitByStockPriceChange(ProfitCalculationDto.ProfitCalculationRequest.builder()
                .stockId(10L)
                .newPrice(150L)
                .build());

        assertThat(valuationRepository.portfolioValuations.get(1L).getCurrentValue()).isEqualTo(501L);
    }

    @Test
    void usesZeroProfitRateWhenPurchasedValueIsZero() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        ProfitCalculateService service = new ProfitCalculateService(
                portfolioStateStore,
                valuationRepository,
                fixture.metrics()
        );

        portfolioStateStore.portfolioIdsByStockId.put(10L, List.of(1L));
        portfolioStateStore.portfolioMetadata.put(1L, new PortfolioStateStore.PortfolioMetadata(0L, 1L, "100", new BigDecimal("500")));
        portfolioStateStore.stockHoldings.put("1:10", new PortfolioStateStore.StockHolding(BigDecimal.TEN, new BigDecimal("1000")));

        service.updateProfitByStockPriceChange(ProfitCalculationDto.ProfitCalculationRequest.builder()
                .stockId(10L)
                .newPrice(150L)
                .build());

        assertThat(valuationRepository.portfolioValuations.get(1L).getProfitRate()).isEqualTo(0.0);
    }

    @Test
    void recordsZeroWorkloadMetricsWhenNoPortfolioIsAffected() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        ProfitCalculateService service = new ProfitCalculateService(
                portfolioStateStore,
                valuationRepository,
                fixture.metrics()
        );

        portfolioStateStore.portfolioIdsByStockId.put(999L, List.of());

        service.updateProfitByStockPriceChange(ProfitCalculationDto.ProfitCalculationRequest.builder()
                .stockId(999L)
                .newPrice(150L)
                .build());

        assertThat(valuationRepository.portfolioValuations).isEmpty();
        assertThat(registry.find(ProfitWorkerMetrics.AFFECTED_USERS)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION)
                .summary()).isNull();
    }

    private static class FakePortfolioStateStore implements PortfolioStateStore {

        final Map<Long, List<Long>> portfolioIdsByStockId = new HashMap<>();
        final Map<Long, PortfolioMetadata> portfolioMetadata = new HashMap<>();
        final Map<String, StockHolding> stockHoldings = new HashMap<>();
        final Map<Long, BigDecimal> currentValues = new HashMap<>();
        int bulkFetchMetadataCalls;
        int bulkIncrementCalls;
        int bulkIncrementAndFetchCalls;

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
            bulkFetchMetadataCalls++;
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
            bulkIncrementCalls++;
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
        public Map<Long, PortfolioStateSnapshot> bulkIncrementCurrentValuesAndFetchMetadata(Map<Long, BigDecimal> deltasByPortfolioId) {
            bulkIncrementAndFetchCalls++;
            Map<Long, PortfolioStateSnapshot> result = new HashMap<>();
            for (var entry : deltasByPortfolioId.entrySet()) {
                Long portfolioId = entry.getKey();
                BigDecimal delta = entry.getValue();
                PortfolioMetadata meta = portfolioMetadata.get(portfolioId);
                BigDecimal oldCV = meta != null ? meta.currentValue() : BigDecimal.ZERO;
                BigDecimal newCV = oldCV.add(delta);
                currentValues.put(portfolioId, newCV);
                result.put(portfolioId, new PortfolioStateSnapshot(newCV, meta));
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


    private static class FakeValuationRepository implements ValuationRepository {

        final Map<Long, PortfolioValuation> portfolioValuations = new ConcurrentHashMap<>();
        int bulkPortfolioSaveCalls;
        int bulkPortfolioPriceUpdateSaveCalls;
        int bulkUserPriceUpdateSaveCalls;

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
            throw new UnsupportedOperationException();
        }

        @Override
        public void bulkSavePortfolioValuations(List<PortfolioValuation> valuations) {
            bulkPortfolioSaveCalls++;
            for (PortfolioValuation v : valuations) {
                portfolioValuations.put(v.getPortfolioId(), v);
            }
        }

        @Override
        public void bulkSaveUserValuations(List<UserValuation> valuations) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void bulkSavePortfolioPriceUpdateValuations(List<PortfolioValuation> valuations) {
            bulkPortfolioPriceUpdateSaveCalls++;
            for (PortfolioValuation v : valuations) {
                portfolioValuations.put(v.getPortfolioId(), v);
            }
        }

        @Override
        public void bulkSaveUserPriceUpdateValuations(List<UserValuation> valuations) {
            bulkUserPriceUpdateSaveCalls++;
        }
    }
}
