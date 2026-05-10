package depth.finvibe.profit.worker.application.port.in;

import depth.finvibe.profit.worker.dto.CacheUpdateDto;

public interface CacheUpdateUseCase {
    void updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest req);

    void updateUserCache(CacheUpdateDto.UserCacheUpdateRequest request);
}
