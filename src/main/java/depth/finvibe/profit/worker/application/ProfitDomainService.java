package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.exception.ProfitCacheMissException;
import depth.finvibe.profit.worker.application.exception.ProfitCacheMissReason;
import depth.finvibe.profit.worker.application.port.in.ProfitUseCase;
import depth.finvibe.profit.worker.application.port.out.PortfolioProfitRepository;
import depth.finvibe.profit.worker.application.port.out.PortfolioProfitUpdateResult;
import depth.finvibe.profit.worker.application.port.out.PortfolioStockOwnershipRepository;
import depth.finvibe.profit.worker.application.port.out.PortfolioUserOwnershipRepository;
import depth.finvibe.profit.worker.application.port.out.UserProfitRepository;
import depth.finvibe.profit.worker.dto.ProfitDto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

@Service
public class ProfitDomainService implements ProfitUseCase {
    private final PortfolioStockOwnershipRepository portfolioStockOwnershipRepository;
    private final PortfolioUserOwnershipRepository portfolioUserOwnershipRepository;
    private final PortfolioProfitRepository portfolioProfitRepository;
    private final UserProfitRepository userProfitRepository;
    private final ProfitCacheHydrationService profitCacheHydrationService;
    private final Executor profitUpdateExecutor;

    @Autowired
    public ProfitDomainService(
            PortfolioStockOwnershipRepository portfolioStockOwnershipRepository,
            PortfolioUserOwnershipRepository portfolioUserOwnershipRepository,
            PortfolioProfitRepository portfolioProfitRepository,
            UserProfitRepository userProfitRepository,
            ObjectProvider<ProfitCacheHydrationService> profitCacheHydrationServiceProvider,
            @Qualifier("profitUpdateExecutor") Executor profitUpdateExecutor
    ) {
        this(
                portfolioStockOwnershipRepository,
                portfolioUserOwnershipRepository,
                portfolioProfitRepository,
                userProfitRepository,
                profitCacheHydrationServiceProvider.getIfAvailable(),
                profitUpdateExecutor
        );
    }

    ProfitDomainService(
            PortfolioStockOwnershipRepository portfolioStockOwnershipRepository,
            PortfolioUserOwnershipRepository portfolioUserOwnershipRepository,
            PortfolioProfitRepository portfolioProfitRepository,
            UserProfitRepository userProfitRepository,
            ProfitCacheHydrationService profitCacheHydrationService,
            Executor profitUpdateExecutor
    ) {
        this.portfolioStockOwnershipRepository = portfolioStockOwnershipRepository;
        this.portfolioUserOwnershipRepository = portfolioUserOwnershipRepository;
        this.portfolioProfitRepository = portfolioProfitRepository;
        this.userProfitRepository = userProfitRepository;
        this.profitCacheHydrationService = profitCacheHydrationService;
        this.profitUpdateExecutor = profitUpdateExecutor;
    }

    @Override
    public void updateProfits(ProfitDto.ProfitRecalculateRequest request) {
        validate(request);

        try {
            updateProfitsFromCache(request);
        } catch (ProfitCacheMissException exception) {
            hydrateCache(exception);
            updateProfitsFromCache(request);
        }
    }

    private void updateProfitsFromCache(ProfitDto.ProfitRecalculateRequest request) {
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

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException exception) {
            throw unwrapCompletionException(exception);
        }
    }

    private void updatePortfolioAndUserProfit(Long portfolioId, ProfitDto.ProfitRecalculateRequest request) {
        PortfolioProfitUpdateResult portfolioResult = portfolioProfitRepository.updateByStockPrice(
                portfolioId,
                request.getStockId(),
                request.getNewPrice()
        );

        Long userId = portfolioUserOwnershipRepository.findUserIdByPortfolioId(portfolioId);
        if (userId == null) {
            throw new ProfitCacheMissException(
                    request.getStockId(),
                    portfolioId,
                    null,
                    ProfitCacheMissReason.PORTFOLIO_OWNER_MISSING
            );
        }

        userProfitRepository.updateReturnRateAndRanking(
                userId,
                portfolioResult.oldPortfolioUnrealizedProfit(),
                portfolioResult.newPortfolioUnrealizedProfit()
        );
    }

    private void hydrateCache(ProfitCacheMissException exception) {
        if (profitCacheHydrationService == null) {
            throw exception;
        }
        profitCacheHydrationService.hydrate(exception);
    }

    private RuntimeException unwrapCompletionException(CompletionException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return exception;
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
