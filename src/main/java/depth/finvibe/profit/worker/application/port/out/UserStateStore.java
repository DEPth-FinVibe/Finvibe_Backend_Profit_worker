package depth.finvibe.profit.worker.application.port.out;

/**
 * 유저 단위 수익률 계산과 캐시 갱신에 필요한 상태 저장소 포트.
 */
public interface UserStateStore {

    /**
     * 포트폴리오를 소유한 유저 ID를 조회한다.
     *
     * @param portfolioId 포트폴리오 ID
     * @return 포트폴리오 소유 유저 ID
     */
    Long findUserIdByPortfolioId(Long portfolioId);

    /**
     * 유저의 총 구매액을 조회한다.
     *
     * @param userId 유저 ID
     * @return 유저 총 구매액
     */
    Long findPurchasedValue(Long userId);

    /**
     * 유저가 보유한 포트폴리오들의 현재 평가액 합계를 계산한다.
     *
     * @param userId 유저 ID
     * @return 유저 현재 평가액
     */
    Long calculateCurrentValue(Long userId);

    /**
     * 유저의 보유 포트폴리오 수를 조회한다.
     *
     * @param userId 유저 ID
     * @return 보유 포트폴리오 수
     */
    Long findPortfolioCount(Long userId);

    /**
     * 포트폴리오와 유저의 소유 관계를 저장한다.
     *
     * @param portfolioId 포트폴리오 ID
     * @param userId 유저 ID
     */
    void mapPortfolioToUser(Long portfolioId, Long userId);

    /**
     * 포트폴리오와 유저의 소유 관계를 제거한다.
     *
     * @param portfolioId 포트폴리오 ID
     */
    void removePortfolioUserMapping(Long portfolioId);

    /**
     * 유저 총 구매액에 금액을 더한다.
     *
     * @param userId 유저 ID
     * @param amount 더할 원화 금액
     */
    void addPurchasedValue(Long userId, Long amount);

    /**
     * 유저 총 구매액에서 금액을 뺀다.
     *
     * @param userId 유저 ID
     * @param amount 뺄 원화 금액
     */
    void subtractPurchasedValue(Long userId, Long amount);

    /**
     * 유저 보유 포트폴리오 수를 1 증가시킨다.
     *
     * @param userId 유저 ID
     */
    void increasePortfolioCount(Long userId);

    /**
     * 유저 보유 포트폴리오 수를 1 감소시킨다.
     *
     * @param userId 유저 ID
     */
    void decreasePortfolioCount(Long userId);
}
