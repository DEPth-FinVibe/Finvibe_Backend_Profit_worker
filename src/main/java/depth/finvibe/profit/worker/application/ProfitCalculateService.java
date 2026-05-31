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
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
        updateProfitsByStockPriceChanges(List.of(request));
    }

    @Override
    public void updateProfitsByStockPriceChanges(List<ProfitCalculationDto.ProfitCalculationRequest> requests) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;

        try {
            // Phase 1: 역인덱스 조회 — 각 이벤트별 영향받는 포트폴리오 수집
            Timer.Sample reverseIndexSample = metrics.startSample();
            List<PortfolioRecalculationTask> tasks = requests.stream()
                    .flatMap(request -> {
                        Long stockId = Objects.requireNonNull(request.getStockId());
                        Long newPrice = Objects.requireNonNull(request.getNewPrice());
                        return portfolioStateStore.findPortfolioIdsByStockId(stockId).stream()
                                .map(portfolioId -> new PortfolioRecalculationTask(portfolioId, stockId, newPrice));
                    })
                    .toList();
            metrics.recordPhaseDuration(
                    ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION,
                    ProfitWorkerMetrics.PHASE_REVERSE_INDEX_LOOKUP,
                    ProfitWorkerMetrics.RESULT_SUCCESS,
                    reverseIndexSample
            );

            // Phase 2: 포트폴리오 재계산 — 모든 이벤트의 작업을 한 번에 병렬 처리
            Timer.Sample portfolioFanoutSample = metrics.startSample();
            Map<String, BigDecimal> userDeltaByUserId = recalculatePortfolios(tasks);
            metrics.recordPhaseDuration(
                    ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION,
                    ProfitWorkerMetrics.PHASE_PORTFOLIO_FANOUT,
                    ProfitWorkerMetrics.RESULT_SUCCESS,
                    portfolioFanoutSample
            );

            // Phase 3: 유저 재계산 — 합산된 delta로 한 번에 병렬 처리
            Timer.Sample userFanoutSample = metrics.startSample();
            recalculateUsers(userDeltaByUserId);
            metrics.recordPhaseDuration(
                    ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION,
                    ProfitWorkerMetrics.PHASE_USER_FANOUT,
                    ProfitWorkerMetrics.RESULT_SUCCESS,
                    userFanoutSample
            );

            metrics.recordAffectedPortfolios(ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION, tasks.size());
            metrics.recordAffectedUsers(ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION, userDeltaByUserId.size());
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordServiceDuration(ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION, result, sample);
        }
    }

    private Map<String, BigDecimal> recalculatePortfolios(List<PortfolioRecalculationTask> tasks) {
        Map<String, BigDecimal> userDeltaByUserId = new ConcurrentHashMap<>();
        List<CompletableFuture<UserDeltaUpdate>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(
                        () -> recalculatePortfolio(task.portfolioId(), task.stockId(), task.newPrice()),
                        recalculationExecutor
                ))
                .toList();
        futures.stream()
                .map(this::joinFuture)
                .filter(Objects::nonNull)
                .forEach(update -> userDeltaByUserId.merge(update.userId(), update.delta(), BigDecimal::add));
        return userDeltaByUserId;
    }

    private UserDeltaUpdate recalculatePortfolio(Long portfolioId, Long stockId, Long newPrice) {
        Long purchasedValue = portfolioStateStore.findPurchasedValue(portfolioId);
        PortfolioStateStore.PortfolioCurrentValueUpdate currentValueUpdate =
                portfolioStateStore.recalculateCurrentValue(portfolioId, stockId, newPrice);
        BigDecimal currentValue = currentValueUpdate.currentValue();

        valuationRepository.savePortfolioValuation(PortfolioValuation.builder()
                .portfolioId(portfolioId)
                .purchasedValue(purchasedValue)
                .currentValue(roundToLong(currentValue))
                .profitRate(calculateProfitRate(purchasedValue, currentValue))
                .assetCount(portfolioStateStore.findAssetCount(portfolioId))
                .build());

        String userId = userStateStore.findUserIdByPortfolioId(portfolioId);
        if (userId == null) {
            return null;
        }
        return new UserDeltaUpdate(userId, currentValueUpdate.delta());
    }

    private void recalculateUsers(Map<String, BigDecimal> userDeltaByUserId) {
        List<CompletableFuture<Void>> futures = userDeltaByUserId.entrySet().stream()
                .map(entry -> CompletableFuture.runAsync(
                        () -> recalculateUser(entry.getKey(), entry.getValue()),
                        recalculationExecutor
                ))
                .toList();
        futures.forEach(this::joinFuture);
    }

    private void recalculateUser(String userId, BigDecimal delta) {
        Long purchasedValue = userStateStore.findPurchasedValue(userId);
        BigDecimal currentValue = userStateStore.addCurrentValue(userId, delta);

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

    private record PortfolioRecalculationTask(Long portfolioId, Long stockId, Long newPrice) {
    }

    private record UserDeltaUpdate(String userId, BigDecimal delta) {
    }
}
