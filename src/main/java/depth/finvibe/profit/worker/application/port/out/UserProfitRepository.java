package depth.finvibe.profit.worker.application.port.out;

public interface UserProfitRepository {
    UserProfitUpdateResult updateReturnRateAndRanking(
            Long userId,
            Double oldPortfolioUnrealizedProfit,
            Double newPortfolioUnrealizedProfit
    );
}
