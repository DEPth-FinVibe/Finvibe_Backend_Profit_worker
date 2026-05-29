package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.in.CacheUpdateUseCase;
import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import depth.finvibe.profit.worker.application.port.out.UserStateStore;
import depth.finvibe.profit.worker.application.port.out.ValuationRepository;
import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;
import depth.finvibe.profit.worker.dto.CacheUpdateDto;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 이벤트가 발생했을때, Valuation을 제외한 캐시 정보를 업데이트하는 서비스 <br /> <br />
 * 업데이트 항목
 * <ul>
 *     <li>종목 ID를 소유한 포트폴리오 ID의 리스트</li>
 *     <li>포트폴리오 총 구매액</li>
 *     <li>포트폴리오의 보유 종목 개수</li>
 *     <li>포트폴리오ID 에 대한 유저 ID 매핑</li>
 *     <li>유저의 총 구매액</li>
 *     <li>유저의 보유 포트폴리오 개수</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CacheUpdateService implements CacheUpdateUseCase {

    private final PortfolioStateStore portfolioStateStore;
    private final UserStateStore userStateStore;
    private final ValuationRepository valuationRepository;
    private final ProfitWorkerMetrics metrics;

    @Override
    public void updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest req) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        Long portfolioId = Objects.requireNonNull(req.getPortfolioId(), "portfolioId must not be null");
        Long stockId = Objects.requireNonNull(req.getStockId(), "stockId must not be null");
        Long price = Objects.requireNonNull(req.getPrice(), "price must not be null");
        Long quantity = Objects.requireNonNull(req.getQuantity(), "quantity must not be null");
        CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType type =
                Objects.requireNonNull(req.getType(), "type must not be null");

        Long amount = price * quantity;

        try {
            long affectedUsers = switch (type) {
                case STOCK_BUY -> updatePortfolioCacheByStockBuy(portfolioId, stockId, quantity, amount);
                case STOCK_SELL -> updatePortfolioCacheByStockSell(portfolioId, stockId, quantity, amount);
            };

            metrics.recordAffectedPortfolios(ProfitWorkerMetrics.OPERATION_PORTFOLIO_CACHE_UPDATE, 1);
            metrics.recordAffectedUsers(ProfitWorkerMetrics.OPERATION_PORTFOLIO_CACHE_UPDATE, affectedUsers);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordServiceDuration(ProfitWorkerMetrics.OPERATION_PORTFOLIO_CACHE_UPDATE, result, sample);
        }
    }

    @Override
    public void updateUserCache(CacheUpdateDto.UserCacheUpdateRequest request) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        String userId = Objects.requireNonNull(request.getUserId(), "userId must not be null");
        Long portfolioId = Objects.requireNonNull(request.getPortfolioId(), "portfolioId must not be null");
        CacheUpdateDto.UserCacheUpdateRequest.ChangeType type =
                Objects.requireNonNull(request.getType(), "type must not be null");

        Long portfolioPurchasedValue = portfolioStateStore.findPurchasedValue(portfolioId);

        try {
            switch (type) {
                case CREATED -> updateUserCacheByPortfolioCreated(userId, portfolioId, portfolioPurchasedValue);
                case DELETED -> updateUserCacheByPortfolioDeleted(userId, portfolioId, portfolioPurchasedValue);
            }

            metrics.recordAffectedPortfolios(ProfitWorkerMetrics.OPERATION_USER_CACHE_UPDATE, 1);
            metrics.recordAffectedUsers(ProfitWorkerMetrics.OPERATION_USER_CACHE_UPDATE, 1);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordServiceDuration(ProfitWorkerMetrics.OPERATION_USER_CACHE_UPDATE, result, sample);
        }
    }

    private long updatePortfolioCacheByStockBuy(Long portfolioId, Long stockId, Long quantity, Long amount) {
        boolean added = portfolioStateStore.increaseStockQuantity(stockId, portfolioId, quantity);
        portfolioStateStore.addPurchasedValue(portfolioId, amount);
        portfolioStateStore.addCurrentValue(portfolioId, amount);
        portfolioStateStore.addStockCurrentValue(stockId, portfolioId, amount);
        String userId = userStateStore.findUserIdByPortfolioId(portfolioId);

        if (userId != null) {
            userStateStore.addPurchasedValue(userId, amount);
        }

        if (added) {
            portfolioStateStore.increaseAssetCount(portfolioId);
        }

        saveValuationSnapshot(portfolioId, userId);
        return userId == null ? 0L : 1L;
    }

    private long updatePortfolioCacheByStockSell(Long portfolioId, Long stockId, Long quantity, Long amount) {
        boolean removed = portfolioStateStore.decreaseStockQuantity(stockId, portfolioId, quantity);
        portfolioStateStore.subtractPurchasedValue(portfolioId, amount);
        portfolioStateStore.subtractCurrentValue(portfolioId, amount);
        portfolioStateStore.subtractStockCurrentValue(stockId, portfolioId, amount);
        String userId = userStateStore.findUserIdByPortfolioId(portfolioId);

        if (userId != null) {
            userStateStore.subtractPurchasedValue(userId, amount);
        }

        if (removed) {
            portfolioStateStore.decreaseAssetCount(portfolioId);
        }

        saveValuationSnapshot(portfolioId, userId);
        return userId == null ? 0L : 1L;
    }

    private void updateUserCacheByPortfolioCreated(String userId, Long portfolioId, Long portfolioPurchasedValue) {
        userStateStore.mapPortfolioToUser(portfolioId, userId);
        userStateStore.addPurchasedValue(userId, portfolioPurchasedValue);
        userStateStore.increasePortfolioCount(userId);
        saveUserValuationSnapshot(userId);
    }

    private void updateUserCacheByPortfolioDeleted(String userId, Long portfolioId, Long portfolioPurchasedValue) {
        userStateStore.removePortfolioUserMapping(portfolioId);
        userStateStore.subtractPurchasedValue(userId, portfolioPurchasedValue);
        userStateStore.decreasePortfolioCount(userId);
        valuationRepository.markPortfolioValuationDeleted(portfolioId);
        portfolioStateStore.deletePortfolioState(portfolioId);
        saveUserValuationSnapshot(userId);
    }

    private void saveValuationSnapshot(Long portfolioId, String userId) {
        savePortfolioValuationSnapshot(portfolioId);

        if (userId != null) {
            saveUserValuationSnapshot(userId);
        }
    }

    private void savePortfolioValuationSnapshot(Long portfolioId) {
        Long purchasedValue = portfolioStateStore.findPurchasedValue(portfolioId);
        Long currentValue = portfolioStateStore.findCurrentValue(portfolioId);

        valuationRepository.savePortfolioValuation(PortfolioValuation.builder()
                .portfolioId(portfolioId)
                .purchasedValue(purchasedValue)
                .currentValue(currentValue)
                .profitRate(calculateProfitRate(purchasedValue, currentValue))
                .assetCount(portfolioStateStore.findAssetCount(portfolioId))
                .build());
    }

    private void saveUserValuationSnapshot(String userId) {
        Long purchasedValue = userStateStore.findPurchasedValue(userId);
        Long currentValue = userStateStore.calculateCurrentValue(userId);

        valuationRepository.saveUserValuation(UserValuation.builder()
                .userId(userId)
                .purchasedValue(purchasedValue)
                .currentValue(currentValue)
                .profitRate(calculateProfitRate(purchasedValue, currentValue))
                .portfolioCount(userStateStore.findPortfolioCount(userId))
                .build());
    }

    private Double calculateProfitRate(Long purchasedValue, Long currentValue) {
        if (purchasedValue == 0L) {
            return 0.0;
        }

        return ((double) (currentValue - purchasedValue) / purchasedValue) * 100;
    }
}
