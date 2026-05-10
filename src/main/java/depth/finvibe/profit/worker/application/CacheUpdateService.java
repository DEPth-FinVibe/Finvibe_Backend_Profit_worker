package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.in.CacheUpdateUseCase;
import depth.finvibe.profit.worker.dto.CacheUpdateDto;

/**
 * 이벤트가 발생했을때, Valuation을 제외한 캐시 정보를 업데이트하는 서비스 <br /> <br />
 * 업데이트 항목
 * <ul>
 *     <li>종목 ID를 소유한 포트폴리오 ID의 리스트</li>
 *     <li>포트폴리오 총 구매액</li>
 *     <li>포트폴리오의 보유 종목 개수</li>
 *     <li>포트폴리오ID 에 대한 유저 ID 매핑</li>
 *     <li>유저의 총 구매액</li>
 *     <li>유저의 보유 포트폴리오 개수</li>
 * </ul>
 */
public class CacheUpdateService implements CacheUpdateUseCase {
    @Override
    public void updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest req) {
        //TODO: implement
    }

    @Override
    public void updateUserCache(CacheUpdateDto.UserCacheUpdateRequest request) {
        //TODO: implement
    }
}
