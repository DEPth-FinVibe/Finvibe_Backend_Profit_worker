package depth.finvibe.profit.worker.application.port.out;

public interface PortfolioUserOwnershipRepository {
    void registerPortfolio(Long portfolioId, Long userId);

    void unregisterPortfolio(Long portfolioId);

    Long findUserIdByPortfolioId(Long portfolioId);
}
