package depth.finvibe.profit.worker.application.port.out;

import depth.finvibe.profit.worker.dto.PortfolioAggregateData;
import depth.finvibe.profit.worker.dto.PortfolioHoldingData;
import depth.finvibe.profit.worker.dto.PortfolioOwnerData;
import depth.finvibe.profit.worker.dto.StockPortfolioMappingData;
import depth.finvibe.profit.worker.dto.UserAggregateData;

public interface MonolithClient {
    StockPortfolioMappingData getStockPortfolioMapping(Long stockId);

    PortfolioOwnerData getPortfolioOwner(Long portfolioId);

    PortfolioHoldingData getPortfolioHolding(Long portfolioId, Long stockId);

    PortfolioAggregateData getPortfolioAggregate(Long portfolioId);

    UserAggregateData getUserAggregate(Long userId);
}
