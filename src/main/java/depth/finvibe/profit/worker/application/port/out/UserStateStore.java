package depth.finvibe.profit.worker.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
    String findUserIdByPortfolioId(Long portfolioId);

    /**
     * 유저의 총 구매액을 조회한다.
     *
     * @param userId 유저 ID
     * @return 유저 총 구매액
     */
    Long findPurchasedValue(String userId);

    /**
     * 유저가 보유한 포트폴리오들의 현재 평가액 합계를 계산한다.
     *
     * @param userId 유저 ID
     * @return 유저 현재 평가액
     */
    BigDecimal calculateCurrentValue(String userId);

    /**
     * 유저 현재 평가액을 조회한다.
     *
     * @param userId 유저 ID
     * @return 유저 현재 평가액
     */
    BigDecimal findCurrentValue(String userId);

    /**
     * 유저 현재 평가액에 delta를 누적 반영한다.
     *
     * @param userId 유저 ID
     * @param delta 반영할 평가액 변화량
     * @return 반영 후 유저 현재 평가액
     */
    BigDecimal addCurrentValue(String userId, BigDecimal delta);

    /**
     * 유저의 보유 포트폴리오 수를 조회한다.
     *
     * @param userId 유저 ID
     * @return 보유 포트폴리오 수
     */
    Long findPortfolioCount(String userId);

    /**
     * 포트폴리오와 유저의 소유 관계를 저장한다.
     *
     * @param portfolioId 포트폴리오 ID
     * @param userId 유저 ID
     */
    void mapPortfolioToUser(Long portfolioId, String userId); // 추후 정수 기반 UserID로 변경

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
    void addPurchasedValue(String userId, Long amount);

    /**
     * 유저 총 구매액에서 금액을 뺀다.
     *
     * @param userId 유저 ID
     * @param amount 뺄 원화 금액
     */
    void subtractPurchasedValue(String userId, Long amount);

    /**
     * 유저 보유 포트폴리오 수를 1 증가시킨다.
     *
     * @param userId 유저 ID
     */
    void increasePortfolioCount(String userId);

    /**
     * 유저 보유 포트폴리오 수를 1 감소시킨다.
     *
     * @param userId 유저 ID
     */
    void decreasePortfolioCount(String userId);

    /**
     * 여러 유저의 메타데이터(구매액, 포트폴리오수)를 일괄 조회한다.
     *
     * @param userIds 유저 ID 목록
     * @return userId → UserMetadata 매핑
     */
    Map<String, UserMetadata> bulkFetchUserMetadata(List<String> userIds);

    /**
     * 여러 유저의 현재 평가액에 delta를 원자적으로 누적 반영한다 (pipeline).
     *
     * @param deltasByUserId userId → delta 매핑
     * @return userId → 반영 후 평가액 매핑
     */
    Map<String, BigDecimal> bulkIncrementCurrentValues(Map<String, BigDecimal> deltasByUserId);

    /**
     * 여러 유저의 현재 평가액에 delta를 반영하고 메타데이터를 함께 조회한다.
     *
     * <p>기본 구현은 기존 bulk API 둘을 순차 호출한다. Redis adapter는 단일 pipeline으로 최적화할 수 있다.</p>
     *
     * @param deltasByUserId userId → delta 매핑
     * @return userId → 반영 후 평가액과 메타데이터 매핑
     */
    default Map<String, UserStateSnapshot> bulkIncrementCurrentValuesAndFetchMetadata(Map<String, BigDecimal> deltasByUserId) {
        Map<String, BigDecimal> currentValues = bulkIncrementCurrentValues(deltasByUserId);
        Map<String, UserMetadata> metadata = bulkFetchUserMetadata(List.copyOf(deltasByUserId.keySet()));

        Map<String, UserStateSnapshot> result = new java.util.HashMap<>();
        for (String userId : deltasByUserId.keySet()) {
            result.put(userId, new UserStateSnapshot(
                    currentValues.getOrDefault(userId, BigDecimal.ZERO),
                    metadata.get(userId)
            ));
        }
        return result;
    }

    record UserMetadata(Long purchasedValue, Long portfolioCount) {
    }

    record UserStateSnapshot(BigDecimal currentValue, UserMetadata metadata) {
    }
}
