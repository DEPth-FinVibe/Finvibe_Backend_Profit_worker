package depth.finvibe.profit.worker.application.port.out;

import java.util.Set;

public interface PortfolioStockOwnershipRepository {
    void registerPortfolioTo(Long stockId, Long portfolioId);

    void unregisterPortfolioFrom(Long stockId, Long portfolioId);

    Set<Long> findPortfolioIdsByStockId(Long stockId);
}
