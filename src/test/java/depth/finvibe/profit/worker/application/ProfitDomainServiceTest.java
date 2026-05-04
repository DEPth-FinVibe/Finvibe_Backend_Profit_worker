package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.out.PortfolioProfitRepository;
import depth.finvibe.profit.worker.application.port.out.PortfolioProfitUpdateResult;
import depth.finvibe.profit.worker.application.port.out.PortfolioStockOwnershipRepository;
import depth.finvibe.profit.worker.application.port.out.PortfolioUserOwnershipRepository;
import depth.finvibe.profit.worker.application.port.out.UserProfitRepository;
import depth.finvibe.profit.worker.dto.ProfitDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfitDomainServiceTest {
    @Mock
    private PortfolioStockOwnershipRepository portfolioStockOwnershipRepository;

    @Mock
    private PortfolioUserOwnershipRepository portfolioUserOwnershipRepository;

    @Mock
    private PortfolioProfitRepository portfolioProfitRepository;

    @Mock
    private UserProfitRepository userProfitRepository;

    private final Executor profitUpdateExecutor = Runnable::run;

    private ProfitDomainService profitDomainService;

    @BeforeEach
    void setUp() {
        profitDomainService = new ProfitDomainService(
                portfolioStockOwnershipRepository,
                portfolioUserOwnershipRepository,
                portfolioProfitRepository,
                userProfitRepository,
                profitUpdateExecutor
        );
    }

    @Test
    void updateProfitsReturnsWhenNoPortfolioOwnsStock() {
        ProfitDto.ProfitRecalculateRequest request = request(1L, 50_000.0);

        when(portfolioStockOwnershipRepository.findPortfolioIdsByStockId(1L))
                .thenReturn(Collections.emptySet());

        profitDomainService.updateProfits(request);

        verify(portfolioProfitRepository, never()).updateByStockPrice(1L, 1L, 50_000.0);
    }

    @Test
    void updateProfitsUpdatesPortfolioThenUser() {
        ProfitDto.ProfitRecalculateRequest request = request(1L, 50_000.0);

        when(portfolioStockOwnershipRepository.findPortfolioIdsByStockId(1L))
                .thenReturn(Set.of(10L));
        when(portfolioProfitRepository.updateByStockPrice(10L, 1L, 50_000.0))
                .thenReturn(new PortfolioProfitUpdateResult(1_000.0, 2_000.0, 0.1, 0.2));
        when(portfolioUserOwnershipRepository.findUserIdByPortfolioId(10L))
                .thenReturn(100L);

        profitDomainService.updateProfits(request);

        verify(userProfitRepository).updateReturnRateAndRanking(100L, 1_000.0, 2_000.0);
    }

    private ProfitDto.ProfitRecalculateRequest request(Long stockId, Double newPrice) {
        return ProfitDto.ProfitRecalculateRequest.builder()
                .stockId(stockId)
                .newPrice(newPrice)
                .build();
    }
}
