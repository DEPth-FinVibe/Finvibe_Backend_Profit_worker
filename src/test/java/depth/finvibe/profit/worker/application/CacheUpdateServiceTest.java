package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import depth.finvibe.profit.worker.application.port.out.UserStateStore;
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
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore);

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
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore);

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
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore);

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
    }

    @Test
    void updatesUserCacheByPortfolioCreatedAndDeleted() {
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        portfolioStateStore.purchasedValues.put(1L, 1_000L);
        FakeUserStateStore userStateStore = new FakeUserStateStore();
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore);

        service.updateUserCache(CacheUpdateDto.UserCacheUpdateRequest.builder()
                .userId(100L)
                .portfolioId(1L)
                .type(CacheUpdateDto.UserCacheUpdateRequest.ChangeType.CREATED)
                .build());

        assertThat(userStateStore.userIdsByPortfolioId.get(1L)).isEqualTo(100L);
        assertThat(userStateStore.purchasedValues.get(100L)).isEqualTo(1_000L);
        assertThat(userStateStore.portfolioCounts.get(100L)).isEqualTo(1L);

        service.updateUserCache(CacheUpdateDto.UserCacheUpdateRequest.builder()
                .userId(100L)
                .portfolioId(1L)
                .type(CacheUpdateDto.UserCacheUpdateRequest.ChangeType.DELETED)
                .build());

        assertThat(userStateStore.userIdsByPortfolioId).doesNotContainKey(1L);
        assertThat(userStateStore.purchasedValues.get(100L)).isZero();
        assertThat(userStateStore.portfolioCounts.get(100L)).isZero();
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

        private String stockQuantityKey(Long portfolioId, Long stockId) {
            return portfolioId + ":" + stockId;
        }
    }

    private static class FakeUserStateStore implements UserStateStore {

        private final Map<Long, Long> userIdsByPortfolioId = new HashMap<>();
        private final Map<Long, Long> purchasedValues = new HashMap<>();
        private final Map<Long, Long> portfolioCounts = new HashMap<>();

        @Override
        public Long findUserIdByPortfolioId(Long portfolioId) {
            return userIdsByPortfolioId.get(portfolioId);
        }

        @Override
        public Long findPurchasedValue(Long userId) {
            return purchasedValues.getOrDefault(userId, 0L);
        }

        @Override
        public Long calculateCurrentValue(Long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long findPortfolioCount(Long userId) {
            return portfolioCounts.getOrDefault(userId, 0L);
        }

        @Override
        public void mapPortfolioToUser(Long portfolioId, Long userId) {
            userIdsByPortfolioId.put(portfolioId, userId);
        }

        @Override
        public void removePortfolioUserMapping(Long portfolioId) {
            userIdsByPortfolioId.remove(portfolioId);
        }

        @Override
        public void addPurchasedValue(Long userId, Long amount) {
            purchasedValues.merge(userId, amount, Long::sum);
        }

        @Override
        public void subtractPurchasedValue(Long userId, Long amount) {
            purchasedValues.merge(userId, -amount, Long::sum);
        }

        @Override
        public void increasePortfolioCount(Long userId) {
            portfolioCounts.merge(userId, 1L, Long::sum);
        }

        @Override
        public void decreasePortfolioCount(Long userId) {
            portfolioCounts.merge(userId, -1L, Long::sum);
        }
    }
}
