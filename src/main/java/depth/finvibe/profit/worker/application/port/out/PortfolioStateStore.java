package depth.finvibe.profit.worker.application.port.out;

import java.util.List;

public interface PortfolioStateStore {

    List<Long> findPortfolioIdsByStockId(Long stockId);

    Long findPurchasedValue(Long portfolioId);

    Long calculateCurrentValue(Long portfolioId, Long changedStockId, Long newPrice);

    Long findAssetCount(Long portfolioId);
}
