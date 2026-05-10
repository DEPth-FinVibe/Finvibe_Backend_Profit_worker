package depth.finvibe.profit.worker.application.port.out;

public interface UserStateStore {

    Long findUserIdByPortfolioId(Long portfolioId);

    Long findPurchasedValue(Long userId);

    Long calculateCurrentValue(Long userId);

    Long findPortfolioCount(Long userId);
}
