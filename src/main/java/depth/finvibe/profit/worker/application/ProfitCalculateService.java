package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.in.ProfitCalculationUseCase;
import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore.PortfolioMetadata;
import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore.StockHolding;
import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore.StockHoldingKey;
import depth.finvibe.profit.worker.application.port.out.UserStateStore;
import depth.finvibe.profit.worker.application.port.out.UserStateStore.UserMetadata;
import depth.finvibe.profit.worker.application.port.out.ValuationRepository;
import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;
import depth.finvibe.profit.worker.dto.ProfitCalculationDto;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 주식 가격이 변동되었을때, 다음의 항목을 갱신한다. <br />
 * - 관련 포트폴리오의 수익률, 평가액 <br />
 * - 관련 유저의 수익률, 평가액
 */
@Service
@RequiredArgsConstructor
public class ProfitCalculateService implements ProfitCalculationUseCase {

    private final PortfolioStateStore portfolioStateStore;
    private final UserStateStore userStateStore;
    private final ValuationRepository valuationRepository;
    private final ProfitWorkerMetrics metrics;

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

            if (tasks.isEmpty()) {
                result = ProfitWorkerMetrics.RESULT_SUCCESS;
                return;
            }

            // Phase 2: 벌크 프리패치 — 모든 포트폴리오 메타데이터 + 종목 보유 정보를 파이프라인으로 일괄 조회
            Timer.Sample prefetchSample = metrics.startSample();
            List<Long> portfolioIds = tasks.stream().map(PortfolioRecalculationTask::portfolioId).distinct().toList();
            List<StockHoldingKey> holdingKeys = tasks.stream()
                    .map(t -> new StockHoldingKey(t.portfolioId(), t.stockId()))
                    .toList();

            Map<Long, PortfolioMetadata> portfolioMetadata = portfolioStateStore.bulkFetchPortfolioMetadata(portfolioIds);
            Map<String, StockHolding> stockHoldings = portfolioStateStore.bulkFetchStockHoldings(holdingKeys);
            metrics.recordPhaseDuration(
                    ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION,
                    "bulk_prefetch",
                    ProfitWorkerMetrics.RESULT_SUCCESS,
                    prefetchSample
            );

            // Phase 3: 인메모리 연산 — 델타 계산 (Redis 호출 없음)
            Timer.Sample computeSample = metrics.startSample();
            Map<Long, BigDecimal> portfolioDeltaSum = new HashMap<>();
            Map<String, BigDecimal> newStockCurrentValues = new HashMap<>();

            for (PortfolioRecalculationTask task : tasks) {
                String holdingKey = task.portfolioId() + ":" + task.stockId();
                StockHolding holding = stockHoldings.getOrDefault(holdingKey, new StockHolding(BigDecimal.ZERO, BigDecimal.ZERO));

                if (holding.quantity().signum() == 0) {
                    continue;
                }

                BigDecimal newStockCV = BigDecimal.valueOf(task.newPrice()).multiply(holding.quantity());
                BigDecimal delta = newStockCV.subtract(holding.currentValue());

                portfolioDeltaSum.merge(task.portfolioId(), delta, BigDecimal::add);
                String stockCVKey = portfolioStateStore.stockCurrentValueKey(task.portfolioId(), task.stockId());
                newStockCurrentValues.put(stockCVKey, newStockCV);
            }
            metrics.recordPhaseDuration(
                    ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION,
                    "in_memory_compute",
                    ProfitWorkerMetrics.RESULT_SUCCESS,
                    computeSample
            );

            // Phase 4: 파이프라인 HINCRBYFLOAT — 포트폴리오 평가액 원자적 갱신
            Timer.Sample portfolioIncrSample = metrics.startSample();
            Map<Long, BigDecimal> newPortfolioCVs = portfolioDeltaSum.isEmpty()
                    ? Map.of()
                    : portfolioStateStore.bulkIncrementCurrentValues(portfolioDeltaSum);
            metrics.recordPhaseDuration(
                    ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION,
                    "pipeline_portfolio_incr",
                    ProfitWorkerMetrics.RESULT_SUCCESS,
                    portfolioIncrSample
            );

            // Phase 5: 파이프라인 SET — 종목별 현재 평가액 일괄 저장
            Timer.Sample stockCVSample = metrics.startSample();
            if (!newStockCurrentValues.isEmpty()) {
                portfolioStateStore.bulkSetStockCurrentValues(newStockCurrentValues);
            }
            metrics.recordPhaseDuration(
                    ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION,
                    "pipeline_stock_cv_set",
                    ProfitWorkerMetrics.RESULT_SUCCESS,
                    stockCVSample
            );

            // Phase 6: 포트폴리오 평가 snapshot 빌드 + 일괄 저장
            Timer.Sample portfolioValSample = metrics.startSample();
            Map<String, BigDecimal> userDeltaByUserId = new HashMap<>();
            List<PortfolioValuation> portfolioValuations = new ArrayList<>();

            for (Long portfolioId : portfolioDeltaSum.keySet()) {
                PortfolioMetadata meta = portfolioMetadata.get(portfolioId);
                BigDecimal newCV = newPortfolioCVs.getOrDefault(portfolioId, meta != null ? meta.currentValue() : BigDecimal.ZERO);
                Long purchasedValue = meta != null ? meta.purchasedValue() : 0L;
                Long assetCount = meta != null ? meta.assetCount() : 0L;
                String userId = meta != null ? meta.userId() : null;

                portfolioValuations.add(PortfolioValuation.builder()
                        .portfolioId(portfolioId)
                        .purchasedValue(purchasedValue)
                        .currentValue(roundToLong(newCV))
                        .profitRate(calculateProfitRate(purchasedValue, newCV))
                        .assetCount(assetCount)
                        .build());

                if (userId != null) {
                    BigDecimal delta = portfolioDeltaSum.get(portfolioId);
                    userDeltaByUserId.merge(userId, delta, BigDecimal::add);
                }
            }
            valuationRepository.bulkSavePortfolioValuations(portfolioValuations);
            metrics.recordPhaseDuration(
                    ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION,
                    ProfitWorkerMetrics.PHASE_PORTFOLIO_FANOUT,
                    ProfitWorkerMetrics.RESULT_SUCCESS,
                    portfolioValSample
            );

            // Phase 7: 유저 재계산 — 벌크 HINCRBYFLOAT + 메타데이터 프리패치 + 일괄 저장
            Timer.Sample userFanoutSample = metrics.startSample();
            if (!userDeltaByUserId.isEmpty()) {
                recalculateUsersBulk(userDeltaByUserId);
            }
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

    private void recalculateUsersBulk(Map<String, BigDecimal> userDeltaByUserId) {
        // Pipeline HINCRBYFLOAT per user — 원자적 평가액 갱신
        Map<String, BigDecimal> newUserCVs = userStateStore.bulkIncrementCurrentValues(userDeltaByUserId);

        // Pipeline HMGET — 유저 메타데이터 일괄 조회
        List<String> userIds = new ArrayList<>(userDeltaByUserId.keySet());
        Map<String, UserMetadata> userMetadata = userStateStore.bulkFetchUserMetadata(userIds);

        // 인메모리 빌드 + 파이프라인 저장
        List<UserValuation> userValuations = new ArrayList<>();
        for (String userId : userIds) {
            UserMetadata meta = userMetadata.get(userId);
            BigDecimal currentValue = newUserCVs.getOrDefault(userId, BigDecimal.ZERO);
            Long purchasedValue = meta != null ? meta.purchasedValue() : 0L;
            Long portfolioCount = meta != null ? meta.portfolioCount() : 0L;

            userValuations.add(UserValuation.builder()
                    .userId(userId)
                    .purchasedValue(purchasedValue)
                    .currentValue(roundToLong(currentValue))
                    .profitRate(calculateProfitRate(purchasedValue, currentValue))
                    .portfolioCount(portfolioCount)
                    .build());
        }
        valuationRepository.bulkSaveUserValuations(userValuations);
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
}
