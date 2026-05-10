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
                .amount(1_000L)
                .build());

        assertThat(portfolioStateStore.stockIdsByPortfolioId.get(1L)).containsExactly(10L);
        assertThat(portfolioStateStore.purchasedValues.get(1L)).isEqualTo(1_000L);
        assertThat(portfolioStateStore.assetCounts.get(1L)).isEqualTo(1L);

        service.updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                .portfolioId(1L)
                .stockId(10L)
                .type(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_BUY)
                .amount(500L)
                .build());

        assertThat(portfolioStateStore.purchasedValues.get(1L)).isEqualTo(1_500L);
        assertThat(portfolioStateStore.assetCounts.get(1L)).isEqualTo(1L);
    }

    @Test
    void updatesPortfolioCacheByStockSell() {
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        portfolioStateStore.stockIdsByPortfolioId.put(1L, new HashSet<>(Set.of(10L)));
        portfolioStateStore.purchasedValues.put(1L, 1_500L);
        portfolioStateStore.assetCounts.put(1L, 1L);
        FakeUserStateStore userStateStore = new FakeUserStateStore();
        CacheUpdateService service = new CacheUpdateService(portfolioStateStore, userStateStore);

        service.updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                .portfolioId(1L)
                .stockId(10L)
                .type(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_SELL)
                .amount(500L)
                .build());

        assertThat(portfolioStateStore.stockIdsByPortfolioId.get(1L)).doesNotContain(10L);
        assertThat(portfolioStateStore.purchasedValues.get(1L)).isEqualTo(1_000L);
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
        private final Map<Long, Long> purchasedValues = new HashMap<>();
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
        public boolean addPortfolioStock(Long stockId, Long portfolioId) {
            return stockIdsByPortfolioId.computeIfAbsent(portfolioId, ignored -> new HashSet<>()).add(stockId);
        }

        @Override
        public boolean removePortfolioStock(Long stockId, Long portfolioId) {
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
        public void increaseAssetCount(Long portfolioId) {
            assetCounts.merge(portfolioId, 1L, Long::sum);
        }

        @Override
        public void decreaseAssetCount(Long portfolioId) {
            assetCounts.merge(portfolioId, -1L, Long::sum);
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
