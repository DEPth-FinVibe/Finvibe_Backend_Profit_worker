package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.in.ProfitCalculationUseCase;
import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import depth.finvibe.profit.worker.application.port.out.UserStateStore;
import depth.finvibe.profit.worker.application.port.out.ValuationRepository;
import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;
import depth.finvibe.profit.worker.dto.ProfitCalculationDto;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        Long stockId = Objects.requireNonNull(request.getStockId(), "stockId must not be null");
        Long newPrice = Objects.requireNonNull(request.getNewPrice(), "newPrice must not be null");

        try {
            List<Long> portfolioIds = portfolioStateStore.findPortfolioIdsByStockId(stockId);
            Set<String> affectedUserIds = new HashSet<>();

            for (Long portfolioId : portfolioIds) {
                Long purchasedValue = portfolioStateStore.findPurchasedValue(portfolioId);
                BigDecimal currentValue = portfolioStateStore.calculateCurrentValue(portfolioId, stockId, newPrice);

                valuationRepository.savePortfolioValuation(PortfolioValuation.builder()
                        .portfolioId(portfolioId)
                        .purchasedValue(purchasedValue)
                        .currentValue(roundToLong(currentValue))
                        .profitRate(calculateProfitRate(purchasedValue, currentValue))
                        .assetCount(portfolioStateStore.findAssetCount(portfolioId))
                        .build());

                String userId = userStateStore.findUserIdByPortfolioId(portfolioId);
                if (userId != null) {
                    affectedUserIds.add(userId);
                }
            }

            for (String userId : affectedUserIds) {
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

            metrics.recordAffectedPortfolios(ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION, portfolioIds.size());
            metrics.recordAffectedUsers(ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION, affectedUserIds.size());
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordServiceDuration(ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION, result, sample);
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
