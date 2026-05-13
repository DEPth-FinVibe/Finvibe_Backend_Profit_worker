package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import depth.finvibe.profit.worker.application.port.out.UserStateStore;
import depth.finvibe.profit.worker.application.port.out.ValuationRepository;
import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;
import depth.finvibe.profit.worker.dto.CacheUpdateDto;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CacheUpdateServiceTest {

    @Test
    void updatesPortfolioCacheByStockBuy() {
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        FakeUserStateStore userStateStore = new FakeUserStateStore();
        userStateStore.userIdsByPortfolioId.put(1L, "100");
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore, valuationRepository);

        service.updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                .portfolioId(1L)
                .stockId(10L)
                .type(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_BUY)
                .price(100L)
                .quantity(10L)
                .build());

        assertThat(portfolioStateStore.stockIdsByPortfolioId.get(1L)).containsExactly(10L);
        assertThat(portfolioStateStore.stockQuantities.get("1:10")).isEqualTo(10L);
        assertThat(portfolioStateStore.stockCurrentValues.get("1:10")).isEqualTo(1_000L);
        assertThat(portfolioStateStore.purchasedValues.get(1L)).isEqualTo(1_000L);
        assertThat(portfolioStateStore.currentValues.get(1L)).isEqualTo(1_000L);
        assertThat(portfolioStateStore.assetCounts.get(1L)).isEqualTo(1L);
        assertThat(userStateStore.purchasedValues.get("100")).isEqualTo(1_000L);
        assertThat(valuationRepository.dirtyPortfolioIds).contains(1L);
        assertThat(valuationRepository.dirtyUserIds).contains("100");

        service.updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                .portfolioId(1L)
                .stockId(10L)
                .type(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_BUY)
                .price(100L)
                .quantity(5L)
                .build());

        assertThat(portfolioStateStore.stockQuantities.get("1:10")).isEqualTo(15L);
        assertThat(portfolioStateStore.stockCurrentValues.get("1:10")).isEqualTo(1_500L);
        assertThat(portfolioStateStore.purchasedValues.get(1L)).isEqualTo(1_500L);
        assertThat(portfolioStateStore.currentValues.get(1L)).isEqualTo(1_500L);
        assertThat(portfolioStateStore.assetCounts.get(1L)).isEqualTo(1L);
        assertThat(userStateStore.purchasedValues.get("100")).isEqualTo(1_500L);
    }

    @Test
    void updatesPortfolioCacheByPartialStockSell() {
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        portfolioStateStore.stockIdsByPortfolioId.put(1L, new HashSet<>(Set.of(10L)));
        portfolioStateStore.stockQuantities.put("1:10", 10L);
        portfolioStateStore.stockCurrentValues.put("1:10", 1_500L);
        portfolioStateStore.purchasedValues.put(1L, 1_500L);
        portfolioStateStore.currentValues.put(1L, 1_500L);
        portfolioStateStore.assetCounts.put(1L, 1L);
        FakeUserStateStore userStateStore = new FakeUserStateStore();
        userStateStore.userIdsByPortfolioId.put(1L, "100");
        userStateStore.purchasedValues.put("100", 1_500L);
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore, valuationRepository);

        service.updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                .portfolioId(1L)
                .stockId(10L)
                .type(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_SELL)
                .price(100L)
                .quantity(5L)
                .build());

        assertThat(portfolioStateStore.stockIdsByPortfolioId.get(1L)).contains(10L);
        assertThat(portfolioStateStore.stockQuantities.get("1:10")).isEqualTo(5L);
        assertThat(portfolioStateStore.stockCurrentValues.get("1:10")).isEqualTo(1_000L);
        assertThat(portfolioStateStore.purchasedValues.get(1L)).isEqualTo(1_000L);
        assertThat(portfolioStateStore.currentValues.get(1L)).isEqualTo(1_000L);
        assertThat(portfolioStateStore.assetCounts.get(1L)).isEqualTo(1L);
        assertThat(userStateStore.purchasedValues.get("100")).isEqualTo(1_000L);
        assertThat(valuationRepository.dirtyPortfolioIds).contains(1L);
        assertThat(valuationRepository.dirtyUserIds).contains("100");
    }

    @Test
    void updatesPortfolioCacheByFullStockSell() {
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        portfolioStateStore.stockIdsByPortfolioId.put(1L, new HashSet<>(Set.of(10L)));
        portfolioStateStore.stockQuantities.put("1:10", 5L);
        portfolioStateStore.stockCurrentValues.put("1:10", 500L);
        portfolioStateStore.purchasedValues.put(1L, 500L);
        portfolioStateStore.currentValues.put(1L, 500L);
        portfolioStateStore.assetCounts.put(1L, 1L);
        FakeUserStateStore userStateStore = new FakeUserStateStore();
        userStateStore.userIdsByPortfolioId.put(1L, "100");
        userStateStore.purchasedValues.put("100", 500L);
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore, valuationRepository);

        service.updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                .portfolioId(1L)
                .stockId(10L)
                .type(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_SELL)
                .price(100L)
                .quantity(5L)
                .build());

        assertThat(portfolioStateStore.stockIdsByPortfolioId.get(1L)).doesNotContain(10L);
        assertThat(portfolioStateStore.stockQuantities).doesNotContainKey("1:10");
        assertThat(portfolioStateStore.stockCurrentValues).doesNotContainKey("1:10");
        assertThat(portfolioStateStore.purchasedValues.get(1L)).isZero();
        assertThat(portfolioStateStore.currentValues.get(1L)).isZero();
        assertThat(portfolioStateStore.assetCounts.get(1L)).isZero();
        assertThat(userStateStore.purchasedValues.get("100")).isZero();
        assertThat(valuationRepository.dirtyPortfolioIds).contains(1L);
        assertThat(valuationRepository.dirtyUserIds).contains("100");
    }

    @Test
    void updatesUserCacheByPortfolioCreatedAndDeleted() {
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        portfolioStateStore.purchasedValues.put(1L, 1_000L);
        portfolioStateStore.currentValues.put(1L, 1_200L);
        portfolioStateStore.stockIdsByPortfolioId.put(1L, new HashSet<>(Set.of(10L)));
        portfolioStateStore.stockQuantities.put("1:10", 10L);
        portfolioStateStore.stockCurrentValues.put("1:10", 1_200L);
        FakeUserStateStore userStateStore = new FakeUserStateStore();
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore, valuationRepository);

        service.updateUserCache(CacheUpdateDto.UserCacheUpdateRequest.builder()
                .userId("100")
                .portfolioId(1L)
                .type(CacheUpdateDto.UserCacheUpdateRequest.ChangeType.CREATED)
                .build());

        assertThat(userStateStore.userIdsByPortfolioId.get(1L)).isEqualTo("100");
        assertThat(userStateStore.purchasedValues.get("100")).isEqualTo(1_000L);
        assertThat(userStateStore.portfolioCounts.get("100")).isEqualTo(1L);
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
    }

    private static class FakePortfolioStateStore implements PortfolioStateStore {

        private final Map<Long, Set<Long>> stockIdsByPortfolioId = new HashMap<>();
        private final Map<String, Long> stockQuantities = new HashMap<>();
        private final Map<String, Long> stockCurrentValues = new HashMap<>();
        private final Map<Long, Long> purchasedValues = new HashMap<>();
        private final Map<Long, Long> currentValues = new HashMap<>();
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
        public Long findCurrentValue(Long portfolioId) {
            return currentValues.getOrDefault(portfolioId, 0L);
        }

        @Override
        public Long calculateCurrentValue(Long portfolioId, Long changedStockId, Long newPrice) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long findAssetCount(Long portfolioId) {
            return assetCounts.getOrDefault(portfolioId, 0L);
        }

        @Override
        public boolean increaseStockQuantity(Long stockId, Long portfolioId, Long quantity) {
            String key = stockQuantityKey(portfolioId, stockId);
            Long previousQuantity = stockQuantities.getOrDefault(key, 0L);
            stockQuantities.put(key, previousQuantity + quantity);
            stockIdsByPortfolioId.computeIfAbsent(portfolioId, ignored -> new HashSet<>()).add(stockId);
            return previousQuantity == 0L;
        }

        @Override
        public boolean decreaseStockQuantity(Long stockId, Long portfolioId, Long quantity) {
            String key = stockQuantityKey(portfolioId, stockId);
            Long nextQuantity = stockQuantities.getOrDefault(key, 0L) - quantity;
            if (nextQuantity > 0L) {
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
        public void addCurrentValue(Long portfolioId, Long amount) {
            currentValues.merge(portfolioId, amount, Long::sum);
        }

        @Override
        public void subtractCurrentValue(Long portfolioId, Long amount) {
            currentValues.merge(portfolioId, -amount, Long::sum);
        }

        @Override
        public void addStockCurrentValue(Long stockId, Long portfolioId, Long amount) {
            stockCurrentValues.merge(stockQuantityKey(portfolioId, stockId), amount, Long::sum);
        }

        @Override
        public void subtractStockCurrentValue(Long stockId, Long portfolioId, Long amount) {
            String key = stockQuantityKey(portfolioId, stockId);
            Long nextValue = stockCurrentValues.getOrDefault(key, 0L) - amount;
            if (nextValue > 0L) {
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

        private String stockQuantityKey(Long portfolioId, Long stockId) {
            return portfolioId + ":" + stockId;
        }
    }

    private static class FakeUserStateStore implements UserStateStore {

        private final Map<Long, String> userIdsByPortfolioId = new HashMap<>();
        private final Map<String, Long> purchasedValues = new HashMap<>();
        private final Map<String, Long> portfolioCounts = new HashMap<>();

        @Override
        public String findUserIdByPortfolioId(Long portfolioId) {
            return userIdsByPortfolioId.get(portfolioId);
        }

        @Override
        public Long findPurchasedValue(String userId) {
            return purchasedValues.getOrDefault(userId, 0L);
        }

        @Override
        public Long calculateCurrentValue(String userId) {
            return 0L;
        }

        @Override
        public Long findPortfolioCount(String userId) {
            return portfolioCounts.getOrDefault(userId, 0L);
        }

        @Override
        public void mapPortfolioToUser(Long portfolioId, String userId) {
            userIdsByPortfolioId.put(portfolioId, userId);
        }

        @Override
        public void removePortfolioUserMapping(Long portfolioId) {
            userIdsByPortfolioId.remove(portfolioId);
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
    }

    private static class FakeValuationRepository implements ValuationRepository {

        private final Set<Long> dirtyPortfolioIds = new HashSet<>();
        private final Set<String> dirtyUserIds = new HashSet<>();

        @Override
        public void savePortfolioValuation(PortfolioValuation valuation) {
            dirtyPortfolioIds.add(valuation.getPortfolioId());
        }

        @Override
        public void saveUserValuation(UserValuation valuation) {
            dirtyUserIds.add(valuation.getUserId());
        }
    }
}
