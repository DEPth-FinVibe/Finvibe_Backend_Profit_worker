package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.in.ProfitUseCase;
import depth.finvibe.profit.worker.dto.ProfitDto;
import depth.finvibe.profit.worker.infra.redis.PortfolioProfitRedisRepository;
import depth.finvibe.profit.worker.infra.redis.PortfolioProfitUpdateResult;
import depth.finvibe.profit.worker.infra.redis.PortfolioStockOwnershipRedisRepository;
import depth.finvibe.profit.worker.infra.redis.PortfolioUserOwnershipRedisRepository;
import depth.finvibe.profit.worker.infra.redis.UserProfitRedisRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class ProfitDomainService implements ProfitUseCase {
    private final PortfolioStockOwnershipRedisRepository portfolioStockOwnershipRepository;
    private final PortfolioUserOwnershipRedisRepository portfolioUserOwnershipRepository;
    private final PortfolioProfitRedisRepository portfolioProfitRepository;
    private final UserProfitRedisRepository userProfitRepository;
    private final Executor profitUpdateExecutor;

    public ProfitDomainService(
            PortfolioStockOwnershipRedisRepository portfolioStockOwnershipRepository,
            PortfolioUserOwnershipRedisRepository portfolioUserOwnershipRepository,
            PortfolioProfitRedisRepository portfolioProfitRepository,
            UserProfitRedisRepository userProfitRepository,
            @Qualifier("profitUpdateExecutor") Executor profitUpdateExecutor
    ) {
        this.portfolioStockOwnershipRepository = portfolioStockOwnershipRepository;
        this.portfolioUserOwnershipRepository = portfolioUserOwnershipRepository;
        this.portfolioProfitRepository = portfolioProfitRepository;
        this.userProfitRepository = userProfitRepository;
        this.profitUpdateExecutor = profitUpdateExecutor;
    }

    @Override
    public void updateProfits(ProfitDto.ProfitRecalculateRequest request) {
        validate(request);

        Set<Long> portfolioIds = portfolioStockOwnershipRepository.findPortfolioIdsByStockId(request.getStockId());
        if (portfolioIds.isEmpty()) {
            return;
        }

        List<CompletableFuture<Void>> futures = portfolioIds.stream()
                .map(portfolioId -> CompletableFuture.runAsync(
                        () -> updatePortfolioAndUserProfit(portfolioId, request),
                        profitUpdateExecutor
                ))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void updatePortfolioAndUserProfit(Long portfolioId, ProfitDto.ProfitRecalculateRequest request) {
        PortfolioProfitUpdateResult portfolioResult = portfolioProfitRepository.updateByStockPrice(
                portfolioId,
                request.getStockId(),
                request.getNewPrice()
        );

        Long userId = portfolioUserOwnershipRepository.findUserIdByPortfolioId(portfolioId);
        if (userId == null) {
            throw new IllegalStateException("Portfolio owner mapping not found: portfolioId=" + portfolioId);
        }

        userProfitRepository.updateReturnRateAndRanking(
                userId,
                portfolioResult.oldPortfolioUnrealizedProfit(),
                portfolioResult.newPortfolioUnrealizedProfit()
        );
    }

    private void validate(ProfitDto.ProfitRecalculateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Profit recalculation request must not be null");
        }
        if (request.getStockId() == null) {
            throw new IllegalArgumentException("stockId must not be null");
        }
        if (request.getNewPrice() == null || request.getNewPrice() <= 0) {
            throw new IllegalArgumentException("newPrice must be positive");
        }
    }
}
