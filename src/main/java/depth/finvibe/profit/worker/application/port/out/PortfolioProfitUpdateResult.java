package depth.finvibe.profit.worker.application.port.out;

public record PortfolioProfitUpdateResult(
        Double oldPortfolioUnrealizedProfit,
        Double newPortfolioUnrealizedProfit,
        Double oldPortfolioReturnRate,
        Double newPortfolioReturnRate
) {
}
