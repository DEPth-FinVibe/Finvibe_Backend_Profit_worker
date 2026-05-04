package depth.finvibe.profit.worker.infra.redis;

public record PortfolioProfitUpdateResult(
        Double oldPortfolioUnrealizedProfit,
        Double newPortfolioUnrealizedProfit,
        Double oldPortfolioReturnRate,
        Double newPortfolioReturnRate
) {
}
