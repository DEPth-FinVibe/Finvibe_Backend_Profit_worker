package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import depth.finvibe.profit.worker.application.port.out.UserStateStore;
import depth.finvibe.profit.worker.application.port.out.ValuationRepository;
import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;
import depth.finvibe.profit.worker.dto.ProfitCalculationDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfitCalculateServiceTest {

    @Test
    void updatesPortfolioAndUserValuationByStockPriceChange() {
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        FakeUserStateStore userStateStore = new FakeUserStateStore(valuationRepository);
        ProfitCalculateService service = new ProfitCalculateService(
                portfolioStateStore,
                userStateStore,
                valuationRepository
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

        UserValuation userValuation = valuationRepository.userValuations.get(100L);
        assertThat(userValuation.getPurchasedValue()).isEqualTo(2_000L);
        assertThat(userValuation.getCurrentValue()).isEqualTo(2_200L);
        assertThat(userValuation.getProfitRate()).isEqualTo(10.0);
        assertThat(userValuation.getPortfolioCount()).isEqualTo(2L);
    }

    @Test
    void usesZeroProfitRateWhenPurchasedValueIsZero() {
        FakeValuationRepository valuationRepository = new FakeValuationRepository();
        FakePortfolioStateStore portfolioStateStore = new FakePortfolioStateStore();
        portfolioStateStore.purchasedValues.put(1L, 0L);
        portfolioStateStore.currentValues.put(1L, 500L);

        FakeUserStateStore userStateStore = new FakeUserStateStore(valuationRepository);
        userStateStore.purchasedValues.put(100L, 0L);
        ProfitCalculateService service = new ProfitCalculateService(
                portfolioStateStore,
                userStateStore,
                valuationRepository
        );

        service.updateProfitByStockPriceChange(ProfitCalculationDto.ProfitCalculationRequest.builder()
                .stockId(10L)
                .newPrice(150L)
                .build());

        assertThat(valuationRepository.portfolioValuations.get(1L).getProfitRate()).isEqualTo(0.0);
        assertThat(valuationRepository.userValuations.get(100L).getProfitRate()).isEqualTo(0.0);
    }

    private static class FakePortfolioStateStore implements PortfolioStateStore {

        private final Map<Long, List<Long>> portfolioIdsByStockId = Map.of(10L, List.of(1L, 2L));
        private final Map<Long, Long> purchasedValues = new HashMap<>(Map.of(1L, 1_000L, 2L, 1_000L));
        private final Map<Long, Long> currentValues = new HashMap<>(Map.of(1L, 1_500L, 2L, 700L));
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
        public Long findCurrentValue(Long portfolioId) {
            return currentValues.get(portfolioId);
        }

        @Override
        public Long calculateCurrentValue(Long portfolioId, Long changedStockId, Long newPrice) {
            return currentValues.get(portfolioId);
        }

        @Override
        public Long findAssetCount(Long portfolioId) {
            return assetCounts.get(portfolioId);
        }

        @Override
        public boolean increaseStockQuantity(Long stockId, Long portfolioId, Long quantity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean decreaseStockQuantity(Long stockId, Long portfolioId, Long quantity) {
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
        public void addCurrentValue(Long portfolioId, Long amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subtractCurrentValue(Long portfolioId, Long amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addStockCurrentValue(Long stockId, Long portfolioId, Long amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subtractStockCurrentValue(Long stockId, Long portfolioId, Long amount) {
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
        private final Map<Long, Long> userIdsByPortfolioId = Map.of(1L, 100L, 2L, 100L);
        private final Map<Long, List<Long>> portfolioIdsByUserId = Map.of(100L, List.of(1L, 2L));
        private final Map<Long, Long> purchasedValues = new HashMap<>(Map.of(100L, 2_000L));
        private final Map<Long, Long> portfolioCounts = Map.of(100L, 2L);

        private FakeUserStateStore(FakeValuationRepository valuationRepository) {
            this.valuationRepository = valuationRepository;
        }

        @Override
        public Long findUserIdByPortfolioId(Long portfolioId) {
            return userIdsByPortfolioId.get(portfolioId);
        }

        @Override
        public Long findPurchasedValue(Long userId) {
            return purchasedValues.get(userId);
        }

        @Override
        public Long calculateCurrentValue(Long userId) {
            return portfolioIdsByUserId.get(userId).stream()
                    .map(valuationRepository.portfolioValuations::get)
                    .mapToLong(PortfolioValuation::getCurrentValue)
                    .sum();
        }

        @Override
        public Long findPortfolioCount(Long userId) {
            return portfolioCounts.get(userId);
        }

        @Override
        public void mapPortfolioToUser(Long portfolioId, Long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removePortfolioUserMapping(Long portfolioId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addPurchasedValue(Long userId, Long amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subtractPurchasedValue(Long userId, Long amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void increasePortfolioCount(Long userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void decreasePortfolioCount(Long userId) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FakeValuationRepository implements ValuationRepository {

        private final Map<Long, PortfolioValuation> portfolioValuations = new HashMap<>();
        private final Map<Long, UserValuation> userValuations = new HashMap<>();
        private final List<UserValuation> savedUserValuations = new ArrayList<>();

        @Override
        public void savePortfolioValuation(PortfolioValuation valuation) {
            portfolioValuations.put(valuation.getPortfolioId(), valuation);
        }

        @Override
        public void saveUserValuation(UserValuation valuation) {
            userValuations.put(valuation.getUserId(), valuation);
            savedUserValuations.add(valuation);
        }
    }
}
