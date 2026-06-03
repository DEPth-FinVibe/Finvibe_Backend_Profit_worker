package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import depth.finvibe.profit.worker.application.port.out.UserStateStore;
import depth.finvibe.profit.worker.application.port.out.ValuationRepository;
import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;
import depth.finvibe.profit.worker.dto.CacheUpdateDto;
import depth.finvibe.profit.worker.support.TestMetricsFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CacheUpdateServiceTest {

    @Test
    void updatesPortfolioCacheByStockBuy() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        FakeUserStateStore userStateStore = new FakeUserStateStore(portfolioStateStore);
        userStateStore.userIdsByPortfolioId.put(1L, "100");
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore, valuationRepository, fixture.metrics());

        service.updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                .portfolioId(1L)
                .stockId(10L)
                .type(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_BUY)
                .price(100L)
                .quantity(BigDecimal.TEN)
                .build());

        assertThat(portfolioStateStore.stockIdsByPortfolioId.get(1L)).containsExactly(10L);
        assertThat(portfolioStateStore.stockQuantities.get("1:10")).isEqualByComparingTo("10");
        assertThat(portfolioStateStore.stockCurrentValues.get("1:10")).isEqualByComparingTo("1000");
        assertThat(portfolioStateStore.purchasedValues.get(1L)).isEqualTo(1_000L);
        assertThat(portfolioStateStore.currentValues.get(1L)).isEqualByComparingTo("1000");
        assertThat(portfolioStateStore.assetCounts.get(1L)).isEqualTo(1L);
        assertThat(userStateStore.purchasedValues.get("100")).isEqualTo(1_000L);
        assertThat(valuationRepository.dirtyPortfolioIds).contains(1L);
        assertThat(valuationRepository.dirtyUserIds).contains("100");
        assertThat(registry.find(ProfitWorkerMetrics.AFFECTED_PORTFOLIOS)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_PORTFOLIO_CACHE_UPDATE)
                .summary().totalAmount()).isEqualTo(1.0);
        assertThat(registry.find(ProfitWorkerMetrics.AFFECTED_USERS)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_PORTFOLIO_CACHE_UPDATE)
                .summary().totalAmount()).isEqualTo(1.0);

        service.updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                .portfolioId(1L)
                .stockId(10L)
                .type(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_BUY)
                .price(100L)
                .quantity(new BigDecimal("5.5"))
                .build());

        assertThat(portfolioStateStore.stockQuantities.get("1:10")).isEqualByComparingTo("15.5");
        assertThat(portfolioStateStore.stockCurrentValues.get("1:10")).isEqualByComparingTo("1550");
        assertThat(portfolioStateStore.purchasedValues.get(1L)).isEqualTo(1_550L);
        assertThat(portfolioStateStore.currentValues.get(1L)).isEqualByComparingTo("1550");
        assertThat(portfolioStateStore.assetCounts.get(1L)).isEqualTo(1L);
        assertThat(userStateStore.purchasedValues.get("100")).isEqualTo(1_550L);
    }

    @Test
    void updatesPortfolioCacheByPartialStockSell() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        portfolioStateStore.stockIdsByPortfolioId.put(1L, new HashSet<>(Set.of(10L)));
        portfolioStateStore.stockQuantities.put("1:10", new BigDecimal("10.5"));
        portfolioStateStore.stockCurrentValues.put("1:10", new BigDecimal("1500"));
        portfolioStateStore.purchasedValues.put(1L, 1_500L);
        portfolioStateStore.currentValues.put(1L, new BigDecimal("1500"));
        portfolioStateStore.assetCounts.put(1L, 1L);
        FakeUserStateStore userStateStore = new FakeUserStateStore(portfolioStateStore);
        userStateStore.userIdsByPortfolioId.put(1L, "100");
        userStateStore.purchasedValues.put("100", 1_500L);
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore, valuationRepository, fixture.metrics());

        service.updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                .portfolioId(1L)
                .stockId(10L)
                .type(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_SELL)
                .price(100L)
                .quantity(new BigDecimal("5.25"))
                .build());

        assertThat(portfolioStateStore.stockIdsByPortfolioId.get(1L)).contains(10L);
        assertThat(portfolioStateStore.stockQuantities.get("1:10")).isEqualByComparingTo("5.25");
        assertThat(portfolioStateStore.stockCurrentValues.get("1:10")).isEqualByComparingTo("975");
        assertThat(portfolioStateStore.purchasedValues.get(1L)).isEqualTo(975L);
        assertThat(portfolioStateStore.currentValues.get(1L)).isEqualByComparingTo("975");
        assertThat(portfolioStateStore.assetCounts.get(1L)).isEqualTo(1L);
        assertThat(userStateStore.purchasedValues.get("100")).isEqualTo(975L);
        assertThat(valuationRepository.dirtyPortfolioIds).contains(1L);
        assertThat(valuationRepository.dirtyUserIds).contains("100");
    }

    @Test
    void updatesPortfolioCacheByFullStockSell() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        portfolioStateStore.stockIdsByPortfolioId.put(1L, new HashSet<>(Set.of(10L)));
        portfolioStateStore.stockQuantities.put("1:10", new BigDecimal("5.25"));
        portfolioStateStore.stockCurrentValues.put("1:10", new BigDecimal("525"));
        portfolioStateStore.purchasedValues.put(1L, 525L);
        portfolioStateStore.currentValues.put(1L, new BigDecimal("525"));
        portfolioStateStore.assetCounts.put(1L, 1L);
        FakeUserStateStore userStateStore = new FakeUserStateStore(portfolioStateStore);
        userStateStore.userIdsByPortfolioId.put(1L, "100");
        userStateStore.purchasedValues.put("100", 525L);
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore, valuationRepository, fixture.metrics());

        service.updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                .portfolioId(1L)
                .stockId(10L)
                .type(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_SELL)
                .price(100L)
                .quantity(new BigDecimal("5.25"))
                .build());

        assertThat(portfolioStateStore.stockIdsByPortfolioId.get(1L)).doesNotContain(10L);
        assertThat(portfolioStateStore.stockQuantities).doesNotContainKey("1:10");
        assertThat(portfolioStateStore.stockCurrentValues).doesNotContainKey("1:10");
        assertThat(portfolioStateStore.purchasedValues.get(1L)).isZero();
        assertThat(portfolioStateStore.currentValues.get(1L)).isEqualByComparingTo("0");
        assertThat(portfolioStateStore.assetCounts.get(1L)).isZero();
        assertThat(userStateStore.purchasedValues.get("100")).isZero();
        assertThat(valuationRepository.dirtyPortfolioIds).contains(1L);
        assertThat(valuationRepository.dirtyUserIds).contains("100");
    }

    @Test
    void roundsSnapshotValuesFromFractionalCurrentValue() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        FakeUserStateStore userStateStore = new FakeUserStateStore(portfolioStateStore);
        userStateStore.userIdsByPortfolioId.put(1L, "100");
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore, valuationRepository, fixture.metrics());

        service.updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                .portfolioId(1L)
                .stockId(10L)
                .type(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_BUY)
                .price(99L)
                .quantity(new BigDecimal("1.5"))
                .build());

        assertThat(valuationRepository.portfolioValuations.get(1L).getCurrentValue()).isEqualTo(149L);
        assertThat(valuationRepository.userValuations.get("100").getCurrentValue()).isEqualTo(149L);
    }

    @Test
    void updatesUserCacheByPortfolioCreatedAndDeleted() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        portfolioStateStore.purchasedValues.put(1L, 1_000L);
        portfolioStateStore.currentValues.put(1L, new BigDecimal("1200.5"));
        portfolioStateStore.stockIdsByPortfolioId.put(1L, new HashSet<>(Set.of(10L)));
        portfolioStateStore.stockQuantities.put("1:10", BigDecimal.TEN);
        portfolioStateStore.stockCurrentValues.put("1:10", new BigDecimal("1200.5"));
        FakeUserStateStore userStateStore = new FakeUserStateStore(portfolioStateStore);
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore, valuationRepository, fixture.metrics());

        service.updateUserCache(CacheUpdateDto.UserCacheUpdateRequest.builder()
                .userId("100")
                .portfolioId(1L)
                .type(CacheUpdateDto.UserCacheUpdateRequest.ChangeType.CREATED)
                .build());

        assertThat(userStateStore.userIdsByPortfolioId.get(1L)).isEqualTo("100");
        assertThat(userStateStore.purchasedValues.get("100")).isEqualTo(1_000L);
        assertThat(userStateStore.portfolioCounts.get("100")).isEqualTo(1L);
        assertThat(valuationRepository.userValuations.get("100").getCurrentValue()).isEqualTo(1_201L);
        assertThat(valuationRepository.dirtyUserIds).contains("100");

        service.updateUserCache(CacheUpdateDto.UserCacheUpdateRequest.builder()
                .userId("100")
                .portfolioId(1L)
                .type(CacheUpdateDto.UserCacheUpdateRequest.ChangeType.DELETED)
                .build());

        assertThat(userStateStore.userIdsByPortfolioId).doesNotContainKey(1L);
        assertThat(userStateStore.purchasedValues.get("100")).isZero();
        assertThat(userStateStore.portfolioCounts.get("100")).isZero();
        assertThat(portfolioStateStore.purchasedValues).doesNotContainKey(1L);
        assertThat(portfolioStateStore.currentValues).doesNotContainKey(1L);
        assertThat(portfolioStateStore.stockQuantities).doesNotContainKey("1:10");
        assertThat(portfolioStateStore.stockCurrentValues).doesNotContainKey("1:10");
        assertThat(valuationRepository.deletedPortfolioIds).contains(1L);
        assertThat(registry.find(ProfitWorkerMetrics.AFFECTED_PORTFOLIOS)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_USER_CACHE_UPDATE)
                .summary().count()).isEqualTo(2);
    }

    @Test
    void recordsZeroAffectedUsersWhenPortfolioHasNoMappedUser() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        FakeUserStateStore userStateStore = new FakeUserStateStore(portfolioStateStore);
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore, valuationRepository, fixture.metrics());

        service.updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                .portfolioId(1L)
                .stockId(10L)
                .type(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_BUY)
                .price(100L)
                .quantity(BigDecimal.ONE)
                .build());

        assertThat(registry.find(ProfitWorkerMetrics.AFFECTED_USERS)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_PORTFOLIO_CACHE_UPDATE)
                .summary().totalAmount()).isEqualTo(0.0);
    }

    @Test
    void coalescesValuationSnapshotsForPortfolioTradeBatch() {
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        FakeUserStateStore userStateStore = new FakeUserStateStore(portfolioStateStore);
        userStateStore.userIdsByPortfolioId.put(1L, "100");
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore, valuationRepository, fixture.metrics());

        service.updatePortfolioCaches(List.of(
                CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                        .portfolioId(1L)
                        .stockId(10L)
                        .type(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_BUY)
                        .price(100L)
                        .quantity(BigDecimal.TEN)
                        .build(),
                CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                        .portfolioId(1L)
                        .stockId(10L)
                        .type(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_BUY)
                        .price(100L)
                        .quantity(BigDecimal.ONE)
                        .build()
        ));

        assertThat(portfolioStateStore.stockQuantities.get("1:10")).isEqualByComparingTo("11");
        assertThat(valuationRepository.portfolioValuations.get(1L).getCurrentValue()).isEqualTo(1_100L);
        assertThat(valuationRepository.userValuations.get("100").getCurrentValue()).isEqualTo(1_100L);
        assertThat(valuationRepository.portfolioSaveCount).isEqualTo(1);
        assertThat(valuationRepository.userSaveCount).isEqualTo(1);
    }

    private static class FakePortfolioStateStore implements PortfolioStateStore {

        private final Map<Long, Set<Long>> stockIdsByPortfolioId = new HashMap<>();
        private final Map<String, BigDecimal> stockQuantities = new HashMap<>();
        private final Map<String, BigDecimal> stockCurrentValues = new HashMap<>();
        private final Map<Long, Long> purchasedValues = new HashMap<>();
        private final Map<Long, BigDecimal> currentValues = new HashMap<>();
        private final Map<Long, Long> assetCounts = new HashMap<>();

        @Override
        public List<Long> findPortfolioIdsByStockId(Long stockId) {
            return stockIdsByPortfolioId.entrySet().stream()
                    .filter(entry -> entry.getValue().contains(stockId))
                    .map(Map.Entry::getKey)
                    .toList();
        }

        @Override
        public Long findPurchasedValue(Long portfolioId) {
            return purchasedValues.getOrDefault(portfolioId, 0L);
        }

        @Override
        public BigDecimal findCurrentValue(Long portfolioId) {
            return currentValues.getOrDefault(portfolioId, BigDecimal.ZERO);
        }

        @Override
        public BigDecimal calculateCurrentValue(Long portfolioId, Long changedStockId, Long newPrice) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PortfolioCurrentValueUpdate recalculateCurrentValue(Long portfolioId, Long changedStockId, Long newPrice) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long findAssetCount(Long portfolioId) {
            return assetCounts.getOrDefault(portfolioId, 0L);
        }

        @Override
        public boolean increaseStockQuantity(Long stockId, Long portfolioId, BigDecimal quantity) {
            String key = stockQuantityKey(portfolioId, stockId);
            BigDecimal previousQuantity = stockQuantities.getOrDefault(key, BigDecimal.ZERO);
            stockQuantities.put(key, previousQuantity.add(quantity));
            stockIdsByPortfolioId.computeIfAbsent(portfolioId, ignored -> new HashSet<>()).add(stockId);
            return previousQuantity.signum() == 0;
        }

        @Override
        public boolean decreaseStockQuantity(Long stockId, Long portfolioId, BigDecimal quantity) {
            String key = stockQuantityKey(portfolioId, stockId);
            BigDecimal nextQuantity = stockQuantities.getOrDefault(key, BigDecimal.ZERO).subtract(quantity);
            if (nextQuantity.signum() > 0) {
                stockQuantities.put(key, nextQuantity);
                return false;
            }

            stockQuantities.remove(key);
            Set<Long> stockIds = stockIdsByPortfolioId.get(portfolioId);
            return stockIds != null && stockIds.remove(stockId);
        }

        @Override
        public void addPurchasedValue(Long portfolioId, Long amount) {
            purchasedValues.merge(portfolioId, amount, Long::sum);
        }

        @Override
        public void subtractPurchasedValue(Long portfolioId, Long amount) {
            purchasedValues.merge(portfolioId, -amount, Long::sum);
        }

        @Override
        public void addCurrentValue(Long portfolioId, BigDecimal amount) {
            currentValues.merge(portfolioId, amount, BigDecimal::add);
        }

        @Override
        public void subtractCurrentValue(Long portfolioId, BigDecimal amount) {
            currentValues.merge(portfolioId, amount.negate(), BigDecimal::add);
        }

        @Override
        public void addStockCurrentValue(Long stockId, Long portfolioId, BigDecimal amount) {
            stockCurrentValues.merge(stockQuantityKey(portfolioId, stockId), amount, BigDecimal::add);
        }

        @Override
        public void subtractStockCurrentValue(Long stockId, Long portfolioId, BigDecimal amount) {
            String key = stockQuantityKey(portfolioId, stockId);
            BigDecimal nextValue = stockCurrentValues.getOrDefault(key, BigDecimal.ZERO).subtract(amount);
            if (nextValue.signum() > 0) {
                stockCurrentValues.put(key, nextValue);
            } else {
                stockCurrentValues.remove(key);
            }
        }

        @Override
        public void increaseAssetCount(Long portfolioId) {
            assetCounts.merge(portfolioId, 1L, Long::sum);
        }

        @Override
        public void decreaseAssetCount(Long portfolioId) {
            assetCounts.merge(portfolioId, -1L, Long::sum);
        }

        @Override
        public void deletePortfolioState(Long portfolioId) {
            Set<Long> stockIds = stockIdsByPortfolioId.remove(portfolioId);
            if (stockIds != null) {
                for (Long stockId : stockIds) {
                    stockQuantities.remove(stockQuantityKey(portfolioId, stockId));
                    stockCurrentValues.remove(stockQuantityKey(portfolioId, stockId));
                }
            }
            purchasedValues.remove(portfolioId);
            currentValues.remove(portfolioId);
            assetCounts.remove(portfolioId);
        }

        @Override
        public Map<Long, PortfolioMetadata> bulkFetchPortfolioMetadata(List<Long> portfolioIds) { throw new UnsupportedOperationException(); }
        @Override
        public Map<String, StockHolding> bulkFetchStockHoldings(List<StockHoldingKey> tasks) { throw new UnsupportedOperationException(); }
        @Override
        public void bulkSetStockCurrentValues(Map<String, BigDecimal> updates) { throw new UnsupportedOperationException(); }
        @Override
        public Map<Long, BigDecimal> bulkIncrementCurrentValues(Map<Long, BigDecimal> deltasByPortfolioId) { throw new UnsupportedOperationException(); }
        @Override
        public Map<Long, List<Long>> bulkFindPortfolioIdsByStockIds(List<Long> stockIds) { throw new UnsupportedOperationException(); }
        @Override
        public String stockCurrentValueKey(Long portfolioId, Long stockId) { return portfolioId + ":" + stockId; }

        private String stockQuantityKey(Long portfolioId, Long stockId) {
            return portfolioId + ":" + stockId;
        }
    }

    private static class FakeUserStateStore implements UserStateStore {

        private final FakePortfolioStateStore portfolioStateStore;
        private final Map<Long, String> userIdsByPortfolioId = new HashMap<>();
        private final Map<String, Long> purchasedValues = new HashMap<>();
        private final Map<String, Long> portfolioCounts = new HashMap<>();
        private final Map<String, Set<Long>> portfolioIdsByUserId = new HashMap<>();
        private final Map<String, BigDecimal> currentValues = new HashMap<>();

        private FakeUserStateStore(FakePortfolioStateStore portfolioStateStore) {
            this.portfolioStateStore = portfolioStateStore;
        }

        @Override
        public String findUserIdByPortfolioId(Long portfolioId) {
            return userIdsByPortfolioId.get(portfolioId);
        }

        @Override
        public Long findPurchasedValue(String userId) {
            return purchasedValues.getOrDefault(userId, 0L);
        }

        @Override
        public BigDecimal calculateCurrentValue(String userId) {
            return userIdsByPortfolioId.entrySet().stream()
                    .filter(entry -> userId.equals(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .map(portfolioStateStore.currentValues::get)
                    .filter(value -> value != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        @Override
        public BigDecimal findCurrentValue(String userId) {
            return currentValues.getOrDefault(userId, calculateCurrentValue(userId));
        }

        @Override
        public BigDecimal addCurrentValue(String userId, BigDecimal delta) {
            BigDecimal nextValue = currentValues.getOrDefault(userId, BigDecimal.ZERO).add(delta);
            currentValues.put(userId, nextValue);
            return nextValue;
        }

        @Override
        public Long findPortfolioCount(String userId) {
            return portfolioCounts.getOrDefault(userId, 0L);
        }

        @Override
        public void mapPortfolioToUser(Long portfolioId, String userId) {
            userIdsByPortfolioId.put(portfolioId, userId);
            portfolioIdsByUserId.computeIfAbsent(userId, ignored -> new HashSet<>()).add(portfolioId);
        }

        @Override
        public void removePortfolioUserMapping(Long portfolioId) {
            String userId = userIdsByPortfolioId.remove(portfolioId);
            if (userId != null) {
                Set<Long> portfolioIds = portfolioIdsByUserId.get(userId);
                if (portfolioIds != null) {
                    portfolioIds.remove(portfolioId);
                }
            }
        }

        @Override
        public void addPurchasedValue(String userId, Long amount) {
            purchasedValues.merge(userId, amount, Long::sum);
        }

        @Override
        public void subtractPurchasedValue(String userId, Long amount) {
            purchasedValues.merge(userId, -amount, Long::sum);
        }

        @Override
        public void increasePortfolioCount(String userId) {
            portfolioCounts.merge(userId, 1L, Long::sum);
        }

        @Override
        public void decreasePortfolioCount(String userId) {
            portfolioCounts.merge(userId, -1L, Long::sum);
        }

        @Override
        public Map<String, UserMetadata> bulkFetchUserMetadata(List<String> userIds) { throw new UnsupportedOperationException(); }
        @Override
        public Map<String, BigDecimal> bulkIncrementCurrentValues(Map<String, BigDecimal> deltasByUserId) { throw new UnsupportedOperationException(); }
    }

    private static class FakeValuationRepository implements ValuationRepository {

        private final Map<Long, PortfolioValuation> portfolioValuations = new HashMap<>();
        private final Map<String, UserValuation> userValuations = new HashMap<>();
        private final Set<Long> dirtyPortfolioIds = new HashSet<>();
        private final Set<String> dirtyUserIds = new HashSet<>();
        private final Set<Long> deletedPortfolioIds = new HashSet<>();
        private int portfolioSaveCount;
        private int userSaveCount;

        @Override
        public void savePortfolioValuation(PortfolioValuation valuation) {
            portfolioValuations.put(valuation.getPortfolioId(), valuation);
            dirtyPortfolioIds.add(valuation.getPortfolioId());
            portfolioSaveCount++;
        }

        @Override
        public void markPortfolioValuationDeleted(Long portfolioId) {
            deletedPortfolioIds.add(portfolioId);
        }

        @Override
        public void saveUserValuation(UserValuation valuation) {
            userValuations.put(valuation.getUserId(), valuation);
            dirtyUserIds.add(valuation.getUserId());
            userSaveCount++;
        }

        @Override
        public void bulkSavePortfolioValuations(List<PortfolioValuation> valuations) {
            for (PortfolioValuation v : valuations) savePortfolioValuation(v);
        }
        @Override
        public void bulkSaveUserValuations(List<UserValuation> valuations) {
            for (UserValuation v : valuations) saveUserValuation(v);
        }
    }
}
