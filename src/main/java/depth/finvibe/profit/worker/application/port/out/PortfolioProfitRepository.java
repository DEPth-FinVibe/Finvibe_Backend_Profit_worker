package depth.finvibe.profit.worker.application.port.out;

public interface PortfolioProfitRepository {
    PortfolioProfitUpdateResult updateByStockPrice(Long portfolioId, Long stockId, Double newPrice);
}
