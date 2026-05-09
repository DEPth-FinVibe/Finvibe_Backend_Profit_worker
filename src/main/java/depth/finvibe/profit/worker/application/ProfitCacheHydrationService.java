package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.exception.ProfitCacheMissException;
import depth.finvibe.profit.worker.application.port.out.MonolithClient;
import depth.finvibe.profit.worker.application.port.out.ProfitCacheWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class ProfitCacheHydrationService {
    private final MonolithClient monolithClient;
    private final ProfitCacheWriter profitCacheWriter;

    public ProfitCacheHydrationService(
            ObjectProvider<MonolithClient> monolithClientProvider,
            ObjectProvider<ProfitCacheWriter> profitCacheWriterProvider
    ) {
        this.monolithClient = monolithClientProvider.getIfAvailable();
        this.profitCacheWriter = profitCacheWriterProvider.getIfAvailable();
    }

    public void hydrate(ProfitCacheMissException exception) {
        if (monolithClient == null || profitCacheWriter == null) {
            throw exception;
        }

        switch (exception.getReason()) {
            case STOCK_PORTFOLIO_MAPPING_MISSING -> profitCacheWriter.writeStockPortfolioMapping(
                    monolithClient.getStockPortfolioMapping(exception.getStockId())
            );
            case PORTFOLIO_OWNER_MISSING -> profitCacheWriter.writePortfolioOwner(
                    monolithClient.getPortfolioOwner(exception.getPortfolioId())
            );
            case PORTFOLIO_HOLDING_MISSING -> profitCacheWriter.writePortfolioHolding(
                    monolithClient.getPortfolioHolding(exception.getPortfolioId(), exception.getStockId())
            );
            case PORTFOLIO_AGGREGATE_MISSING -> profitCacheWriter.writePortfolioAggregate(
                    monolithClient.getPortfolioAggregate(exception.getPortfolioId())
            );
            case USER_AGGREGATE_MISSING -> profitCacheWriter.writeUserAggregate(
                    monolithClient.getUserAggregate(exception.getUserId())
            );
        }
    }
}
