package depth.finvibe.profit.worker.application.port.out;

import depth.finvibe.profit.worker.dto.PortfolioAggregateData;
import depth.finvibe.profit.worker.dto.PortfolioHoldingData;
import depth.finvibe.profit.worker.dto.PortfolioOwnerData;
import depth.finvibe.profit.worker.dto.StockPortfolioMappingData;
import depth.finvibe.profit.worker.dto.UserAggregateData;

public interface ProfitCacheWriter {
    void writeStockPortfolioMapping(StockPortfolioMappingData data);

    void writePortfolioOwner(PortfolioOwnerData data);

    void writePortfolioHolding(PortfolioHoldingData data);

    void writePortfolioAggregate(PortfolioAggregateData data);

    void writeUserAggregate(UserAggregateData data);
}
