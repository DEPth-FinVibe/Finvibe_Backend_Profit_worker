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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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

        try {
            PortfolioCacheUpdate update = toPortfolioCacheUpdate(req);
            PortfolioUpdateResult updateResult = applyPortfolioCacheUpdate(update);

            savePortfolioValuationSnapshot(update.portfolioId());
            if (updateResult.userId() != null) {
                saveUserValuationSnapshot(updateResult.userId());
            }

            metrics.recordAffectedPortfolios(ProfitWorkerMetrics.OPERATION_PORTFOLIO_CACHE_UPDATE, 1);
            metrics.recordAffectedUsers(ProfitWorkerMetrics.OPERATION_PORTFOLIO_CACHE_UPDATE, updateResult.affectedUserCount());
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordServiceDuration(ProfitWorkerMetrics.OPERATION_PORTFOLIO_CACHE_UPDATE, result, sample);
        }
    }

    @Override
    public void updatePortfolioCaches(List<CacheUpdateDto.PortfolioCacheUpdateRequest> requests) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;

        try {
            Set<Long> changedPortfolioIds = new LinkedHashSet<>();
            Set<String> changedUserIds = new LinkedHashSet<>();
            long affectedUsers = 0L;

            for (CacheUpdateDto.PortfolioCacheUpdateRequest request : requests) {
                PortfolioCacheUpdate update = toPortfolioCacheUpdate(request);
                PortfolioUpdateResult updateResult = applyPortfolioCacheUpdate(update);

                changedPortfolioIds.add(update.portfolioId());
                if (updateResult.userId() != null) {
                    changedUserIds.add(updateResult.userId());
                }
                affectedUsers += updateResult.affectedUserCount();
            }

            savePortfolioValuationSnapshots(changedPortfolioIds);
            saveUserValuationSnapshots(changedUserIds);

            metrics.recordAffectedPortfolios(ProfitWorkerMetrics.OPERATION_PORTFOLIO_CACHE_UPDATE, requests.size());
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

        try {
            UserCacheUpdate update = toUserCacheUpdate(request);
            applyUserCacheUpdate(update);
            saveUserValuationSnapshot(update.userId());

            metrics.recordAffectedPortfolios(ProfitWorkerMetrics.OPERATION_USER_CACHE_UPDATE, 1);
            metrics.recordAffectedUsers(ProfitWorkerMetrics.OPERATION_USER_CACHE_UPDATE, 1);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordServiceDuration(ProfitWorkerMetrics.OPERATION_USER_CACHE_UPDATE, result, sample);
        }
    }

    @Override
    public void updateUserCaches(List<CacheUpdateDto.UserCacheUpdateRequest> requests) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;

        try {
            Set<String> changedUserIds = new LinkedHashSet<>();

            for (CacheUpdateDto.UserCacheUpdateRequest request : requests) {
                UserCacheUpdate update = toUserCacheUpdate(request);
                applyUserCacheUpdate(update);
                changedUserIds.add(update.userId());
            }

            saveUserValuationSnapshots(changedUserIds);

            metrics.recordAffectedPortfolios(ProfitWorkerMetrics.OPERATION_USER_CACHE_UPDATE, requests.size());
            metrics.recordAffectedUsers(ProfitWorkerMetrics.OPERATION_USER_CACHE_UPDATE, requests.size());
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordServiceDuration(ProfitWorkerMetrics.OPERATION_USER_CACHE_UPDATE, result, sample);
        }
    }

    private PortfolioCacheUpdate toPortfolioCacheUpdate(CacheUpdateDto.PortfolioCacheUpdateRequest req) {
        Long portfolioId = Objects.requireNonNull(req.getPortfolioId(), "portfolioId must not be null");
        Long stockId = Objects.requireNonNull(req.getStockId(), "stockId must not be null");
        Long price = Objects.requireNonNull(req.getPrice(), "price must not be null");
        BigDecimal quantity = Objects.requireNonNull(req.getQuantity(), "quantity must not be null");
        CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType type =
                Objects.requireNonNull(req.getType(), "type must not be null");
        BigDecimal amount = ValuationDecimalSupport.decimalOf(price).multiply(quantity);

        return new PortfolioCacheUpdate(portfolioId, stockId, quantity, amount, type);
    }

    private UserCacheUpdate toUserCacheUpdate(CacheUpdateDto.UserCacheUpdateRequest request) {
        String userId = Objects.requireNonNull(request.getUserId(), "userId must not be null");
        Long portfolioId = Objects.requireNonNull(request.getPortfolioId(), "portfolioId must not be null");
        CacheUpdateDto.UserCacheUpdateRequest.ChangeType type =
                Objects.requireNonNull(request.getType(), "type must not be null");
        Long portfolioPurchasedValue = portfolioStateStore.findPurchasedValue(portfolioId);

        return new UserCacheUpdate(userId, portfolioId, portfolioPurchasedValue, type);
    }

    private PortfolioUpdateResult applyPortfolioCacheUpdate(PortfolioCacheUpdate update) {
        String userId = switch (update.type()) {
            case STOCK_BUY -> updatePortfolioCacheByStockBuy(update);
            case STOCK_SELL -> updatePortfolioCacheByStockSell(update);
        };
        return new PortfolioUpdateResult(userId);
    }

    private String updatePortfolioCacheByStockBuy(PortfolioCacheUpdate update) {
        boolean added = portfolioStateStore.increaseStockQuantity(update.stockId(), update.portfolioId(), update.quantity());
        portfolioStateStore.addPurchasedValue(update.portfolioId(), roundToLong(update.amount()));
        portfolioStateStore.addCurrentValue(update.portfolioId(), update.amount());
        portfolioStateStore.addStockCurrentValue(update.stockId(), update.portfolioId(), update.amount());
        String userId = userStateStore.findUserIdByPortfolioId(update.portfolioId());

        if (added) {
            portfolioStateStore.increaseAssetCount(update.portfolioId());
        }

        if (userId != null) {
            userStateStore.addPurchasedValue(userId, roundToLong(update.amount()));
        }

        return userId;
    }

    private String updatePortfolioCacheByStockSell(PortfolioCacheUpdate update) {
        boolean removed = portfolioStateStore.decreaseStockQuantity(update.stockId(), update.portfolioId(), update.quantity());
        portfolioStateStore.subtractPurchasedValue(update.portfolioId(), roundToLong(update.amount()));
        portfolioStateStore.subtractCurrentValue(update.portfolioId(), update.amount());
        portfolioStateStore.subtractStockCurrentValue(update.stockId(), update.portfolioId(), update.amount());
        String userId = userStateStore.findUserIdByPortfolioId(update.portfolioId());

        if (removed) {
            portfolioStateStore.decreaseAssetCount(update.portfolioId());
        }

        if (userId != null) {
            userStateStore.subtractPurchasedValue(userId, roundToLong(update.amount()));
        }

        return userId;
    }

    private void applyUserCacheUpdate(UserCacheUpdate update) {
        switch (update.type()) {
            case CREATED -> updateUserCacheByPortfolioCreated(update);
            case DELETED -> updateUserCacheByPortfolioDeleted(update);
        }
    }

    private void updateUserCacheByPortfolioCreated(UserCacheUpdate update) {
        userStateStore.mapPortfolioToUser(update.portfolioId(), update.userId());
        userStateStore.addPurchasedValue(update.userId(), update.portfolioPurchasedValue());
        userStateStore.increasePortfolioCount(update.userId());
    }

    private void updateUserCacheByPortfolioDeleted(UserCacheUpdate update) {
        userStateStore.removePortfolioUserMapping(update.portfolioId());
        userStateStore.subtractPurchasedValue(update.userId(), update.portfolioPurchasedValue());
        userStateStore.decreasePortfolioCount(update.userId());
        valuationRepository.markPortfolioValuationDeleted(update.portfolioId());
        portfolioStateStore.deletePortfolioState(update.portfolioId());
    }

    private void savePortfolioValuationSnapshot(Long portfolioId) {
        valuationRepository.savePortfolioValuation(buildPortfolioValuation(portfolioId));
    }

    private void savePortfolioValuationSnapshots(Set<Long> portfolioIds) {
        if (portfolioIds.isEmpty()) {
            return;
        }

        List<PortfolioValuation> valuations = new ArrayList<>();
        for (Long portfolioId : portfolioIds) {
            valuations.add(buildPortfolioValuation(portfolioId));
        }
        valuationRepository.bulkSavePortfolioValuations(valuations);
    }

    private PortfolioValuation buildPortfolioValuation(Long portfolioId) {
        Long purchasedValue = portfolioStateStore.findPurchasedValue(portfolioId);
        BigDecimal currentValue = portfolioStateStore.findCurrentValue(portfolioId);

        return PortfolioValuation.builder()
                .portfolioId(portfolioId)
                .purchasedValue(purchasedValue)
                .currentValue(roundToLong(currentValue))
                .profitRate(calculateProfitRate(purchasedValue, currentValue))
                .assetCount(portfolioStateStore.findAssetCount(portfolioId))
                .build();
    }

    private void saveUserValuationSnapshot(String userId) {
        valuationRepository.saveUserValuation(buildUserValuation(userId));
    }

    private void saveUserValuationSnapshots(Set<String> userIds) {
        if (userIds.isEmpty()) {
            return;
        }

        List<UserValuation> valuations = new ArrayList<>();
        for (String userId : userIds) {
            valuations.add(buildUserValuation(userId));
        }
        valuationRepository.bulkSaveUserValuations(valuations);
    }

    private UserValuation buildUserValuation(String userId) {
        Long purchasedValue = userStateStore.findPurchasedValue(userId);
        BigDecimal currentValue = userStateStore.calculateCurrentValue(userId);

        return UserValuation.builder()
                .userId(userId)
                .purchasedValue(purchasedValue)
                .currentValue(roundToLong(currentValue))
                .profitRate(calculateProfitRate(purchasedValue, currentValue))
                .portfolioCount(userStateStore.findPortfolioCount(userId))
                .build();
    }

    private Double calculateProfitRate(Long purchasedValue, BigDecimal currentValue) {
        if (purchasedValue == 0L) {
            return 0.0;
        }

        return currentValue
                .subtract(ValuationDecimalSupport.decimalOf(purchasedValue))
                .divide(ValuationDecimalSupport.decimalOf(purchasedValue), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    private Long roundToLong(BigDecimal value) {
        return ValuationDecimalSupport.toWholeNumber(value);
    }

    private record PortfolioCacheUpdate(
            Long portfolioId,
            Long stockId,
            BigDecimal quantity,
            BigDecimal amount,
            CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType type
    ) {
    }

    private record PortfolioUpdateResult(String userId) {
        long affectedUserCount() {
            return userId == null ? 0L : 1L;
        }
    }

    private record UserCacheUpdate(
            String userId,
            Long portfolioId,
            Long portfolioPurchasedValue,
            CacheUpdateDto.UserCacheUpdateRequest.ChangeType type
    ) {
    }
}
