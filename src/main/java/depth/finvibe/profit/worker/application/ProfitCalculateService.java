package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.in.ProfitCalculationUseCase;
import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import depth.finvibe.profit.worker.application.port.out.UserStateStore;
import depth.finvibe.profit.worker.application.port.out.ValuationRepository;
import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;
import depth.finvibe.profit.worker.dto.ProfitCalculationDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    @Override
    public void updateProfitByStockPriceChange(ProfitCalculationDto.ProfitCalculationRequest request) {
        Long stockId = Objects.requireNonNull(request.getStockId(), "stockId must not be null");
        Long newPrice = Objects.requireNonNull(request.getNewPrice(), "newPrice must not be null");

        List<Long> portfolioIds = portfolioStateStore.findPortfolioIdsByStockId(stockId);
        Set<String> affectedUserIds = new HashSet<>();

        for (Long portfolioId : portfolioIds) {
            Long purchasedValue = portfolioStateStore.findPurchasedValue(portfolioId);
            Long currentValue = portfolioStateStore.calculateCurrentValue(portfolioId, stockId, newPrice);

            valuationRepository.savePortfolioValuation(PortfolioValuation.builder()
                    .portfolioId(portfolioId)
                    .purchasedValue(purchasedValue)
                    .currentValue(currentValue)
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
            Long currentValue = userStateStore.calculateCurrentValue(userId);

            valuationRepository.saveUserValuation(UserValuation.builder()
                    .userId(userId)
                    .purchasedValue(purchasedValue)
                    .currentValue(currentValue)
                    .profitRate(calculateProfitRate(purchasedValue, currentValue))
                    .portfolioCount(userStateStore.findPortfolioCount(userId))
                    .build());
        }
    }

    private Double calculateProfitRate(Long purchasedValue, Long currentValue) {
        if (purchasedValue == 0L) {
            return 0.0;
        }

        return ((double) (currentValue - purchasedValue) / purchasedValue) * 100;
    }
}
