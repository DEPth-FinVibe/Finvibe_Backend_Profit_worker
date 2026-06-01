package depth.finvibe.profit.worker.application.port.out;

import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;

import java.util.List;

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
     * 포트폴리오 평가 snapshot을 soft delete 대상으로 표시한다.
     *
     * @param portfolioId 삭제된 포트폴리오 ID
     */
    void markPortfolioValuationDeleted(Long portfolioId);

    /**
     * 유저 평가 snapshot을 저장한다.
     *
     * @param valuation 저장할 유저 평가 정보
     */
    void saveUserValuation(UserValuation valuation);

    /**
     * 여러 포트폴리오 평가 snapshot을 일괄 저장한다 (pipeline).
     */
    void bulkSavePortfolioValuations(List<PortfolioValuation> valuations);

    /**
     * 여러 유저 평가 snapshot을 일괄 저장한다 (pipeline).
     */
    void bulkSaveUserValuations(List<UserValuation> valuations);
}
