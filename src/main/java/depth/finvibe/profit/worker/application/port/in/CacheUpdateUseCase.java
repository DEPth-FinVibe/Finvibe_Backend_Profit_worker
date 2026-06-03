package depth.finvibe.profit.worker.application.port.in;

import depth.finvibe.profit.worker.dto.CacheUpdateDto;

import java.util.List;

public interface CacheUpdateUseCase {
    void updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest req);

    default void updatePortfolioCaches(List<CacheUpdateDto.PortfolioCacheUpdateRequest> requests) {
        requests.forEach(this::updatePortfolioCache);
    }

    void updateUserCache(CacheUpdateDto.UserCacheUpdateRequest request);

    default void updateUserCaches(List<CacheUpdateDto.UserCacheUpdateRequest> requests) {
        requests.forEach(this::updateUserCache);
    }
}
