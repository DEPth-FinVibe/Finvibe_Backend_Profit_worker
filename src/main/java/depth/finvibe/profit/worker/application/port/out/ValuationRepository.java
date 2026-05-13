package depth.finvibe.profit.worker.application.port.out;

import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;

/**
 * 계산된 포트폴리오/유저 평가 snapshot을 저장하는 포트.
 */
public interface ValuationRepository {

    /**
     * 포트폴리오 평가 snapshot을 저장한다.
     *
     * @param valuation 저장할 포트폴리오 평가 정보
     */
    void savePortfolioValuation(PortfolioValuation valuation);

    /**
     * 유저 평가 snapshot을 저장한다.
     *
     * @param valuation 저장할 유저 평가 정보
     */
    void saveUserValuation(UserValuation valuation);
}
