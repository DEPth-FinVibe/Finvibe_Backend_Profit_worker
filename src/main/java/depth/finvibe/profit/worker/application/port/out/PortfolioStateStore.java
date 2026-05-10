package depth.finvibe.profit.worker.application.port.out;

import java.util.List;

/**
 * 포트폴리오 단위 수익률 계산과 캐시 갱신에 필요한 상태 저장소 포트.
 */
public interface PortfolioStateStore {

    /**
     * 특정 종목을 보유한 포트폴리오 ID 목록을 조회한다.
     *
     * @param stockId 종목 ID
     * @return 종목을 보유한 포트폴리오 ID 목록
     */
    List<Long> findPortfolioIdsByStockId(Long stockId);

    /**
     * 포트폴리오의 총 구매액을 조회한다.
     *
     * @param portfolioId 포트폴리오 ID
     * @return 포트폴리오 총 구매액
     */
    Long findPurchasedValue(Long portfolioId);

    /**
     * 변경된 종목 가격을 반영한 포트폴리오 평가액을 계산한다.
     *
     * @param portfolioId 포트폴리오 ID
     * @param changedStockId 가격이 변경된 종목 ID
     * @param newPrice 변경된 종목의 신규 가격
     * @return 변경 가격이 반영된 포트폴리오 평가액
     */
    Long calculateCurrentValue(Long portfolioId, Long changedStockId, Long newPrice);

    /**
     * 포트폴리오의 보유 종목 수를 조회한다.
     *
     * @param portfolioId 포트폴리오 ID
     * @return 보유 종목 수
     */
    Long findAssetCount(Long portfolioId);

    /**
     * 종목을 보유 포트폴리오 인덱스에 추가한다.
     *
     * @param stockId 종목 ID
     * @param portfolioId 포트폴리오 ID
     * @return 기존에 없던 관계가 새로 추가되었으면 true
     */
    boolean addPortfolioStock(Long stockId, Long portfolioId);

    /**
     * 종목을 보유 포트폴리오 인덱스에서 제거한다.
     *
     * @param stockId 종목 ID
     * @param portfolioId 포트폴리오 ID
     * @return 기존 관계가 실제로 제거되었으면 true
     */
    boolean removePortfolioStock(Long stockId, Long portfolioId);

    /**
     * 포트폴리오 총 구매액에 금액을 더한다.
     *
     * @param portfolioId 포트폴리오 ID
     * @param amount 더할 원화 금액
     */
    void addPurchasedValue(Long portfolioId, Long amount);

    /**
     * 포트폴리오 총 구매액에서 금액을 뺀다.
     *
     * @param portfolioId 포트폴리오 ID
     * @param amount 뺄 원화 금액
     */
    void subtractPurchasedValue(Long portfolioId, Long amount);

    /**
     * 포트폴리오 보유 종목 수를 1 증가시킨다.
     *
     * @param portfolioId 포트폴리오 ID
     */
    void increaseAssetCount(Long portfolioId);

    /**
     * 포트폴리오 보유 종목 수를 1 감소시킨다.
     *
     * @param portfolioId 포트폴리오 ID
     */
    void decreaseAssetCount(Long portfolioId);
}
