package depth.finvibe.profit.worker.application.port.out;

import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;

public interface ValuationRepository {

    void savePortfolioValuation(PortfolioValuation valuation);

    void saveUserValuation(UserValuation valuation);
}
