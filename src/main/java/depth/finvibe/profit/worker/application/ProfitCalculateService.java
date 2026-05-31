package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.in.ProfitCalculationUseCase;
import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import depth.finvibe.profit.worker.application.port.out.UserStateStore;
import depth.finvibe.profit.worker.application.port.out.ValuationRepository;
import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;
import depth.finvibe.profit.worker.dto.ProfitCalculationDto;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 주식 가격이 변동되었을때, 다음의 항목을 갱신한다. <br />
 * - 관련 포트폴리오의 수익률, 평가액 <br />
 * - 관련 유저의 수익률, 평가액
 */
@Service
public class ProfitCalculateService implements ProfitCalculationUseCase {

    private final PortfolioStateStore portfolioStateStore;
    private final UserStateStore userStateStore;
    private final ValuationRepository valuationRepository;
    private final ProfitWorkerMetrics metrics;
    private final Executor recalculationExecutor;

    public ProfitCalculateService(
            PortfolioStateStore portfolioStateStore,
            UserStateStore userStateStore,
            ValuationRepository valuationRepository,
            ProfitWorkerMetrics metrics,
            @Qualifier("profitRecalculationExecutor") Executor recalculationExecutor
    ) {
        this.portfolioStateStore = portfolioStateStore;
        this.userStateStore = userStateStore;
        this.valuationRepository = valuationRepository;
        this.metrics = metrics;
        this.recalculationExecutor = recalculationExecutor;
    }

    @Override
    public void updateProfitByStockPriceChange(ProfitCalculationDto.ProfitCalculationRequest request) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        Long stockId = Objects.requireNonNull(request.getStockId(), "stockId must not be null");
        Long newPrice = Objects.requireNonNull(request.getNewPrice(), "newPrice must not be null");

        try {
            List<Long> portfolioIds = portfolioStateStore.findPortfolioIdsByStockId(stockId);
            Set<String> affectedUserIds = recalculatePortfolios(portfolioIds, stockId, newPrice);
            recalculateUsers(affectedUserIds);

            metrics.recordAffectedPortfolios(ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION, portfolioIds.size());
            metrics.recordAffectedUsers(ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION, affectedUserIds.size());
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordServiceDuration(ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION, result, sample);
        }
    }

    private Set<String> recalculatePortfolios(List<Long> portfolioIds, Long stockId, Long newPrice) {
        return portfolioIds.stream()
                .map(portfolioId -> CompletableFuture.supplyAsync(
                        () -> recalculatePortfolio(portfolioId, stockId, newPrice),
                        recalculationExecutor
                ))
                .map(this::joinFuture)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private String recalculatePortfolio(Long portfolioId, Long stockId, Long newPrice) {
        Long purchasedValue = portfolioStateStore.findPurchasedValue(portfolioId);
        BigDecimal currentValue = portfolioStateStore.calculateCurrentValue(portfolioId, stockId, newPrice);

        valuationRepository.savePortfolioValuation(PortfolioValuation.builder()
                .portfolioId(portfolioId)
                .purchasedValue(purchasedValue)
                .currentValue(roundToLong(currentValue))
                .profitRate(calculateProfitRate(purchasedValue, currentValue))
                .assetCount(portfolioStateStore.findAssetCount(portfolioId))
                .build());

        return userStateStore.findUserIdByPortfolioId(portfolioId);
    }

    private void recalculateUsers(Set<String> affectedUserIds) {
        affectedUserIds.stream()
                .map(userId -> CompletableFuture.runAsync(() -> recalculateUser(userId), recalculationExecutor))
                .forEach(this::joinFuture);
    }

    private void recalculateUser(String userId) {
        Long purchasedValue = userStateStore.findPurchasedValue(userId);
        BigDecimal currentValue = userStateStore.calculateCurrentValue(userId);

        valuationRepository.saveUserValuation(UserValuation.builder()
                .userId(userId)
                .purchasedValue(purchasedValue)
                .currentValue(roundToLong(currentValue))
                .profitRate(calculateProfitRate(purchasedValue, currentValue))
                .portfolioCount(userStateStore.findPortfolioCount(userId))
                .build());
    }

    private <T> T joinFuture(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
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
}
