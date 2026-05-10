package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.port.in.CacheUpdateUseCase;
import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import depth.finvibe.profit.worker.application.port.out.UserStateStore;
import depth.finvibe.profit.worker.dto.CacheUpdateDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

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
@Service
@RequiredArgsConstructor
public class CacheUpdateService implements CacheUpdateUseCase {

    private final PortfolioStateStore portfolioStateStore;
    private final UserStateStore userStateStore;

    @Override
    public void updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest req) {
        Long portfolioId = Objects.requireNonNull(req.getPortfolioId(), "portfolioId must not be null");
        Long stockId = Objects.requireNonNull(req.getStockId(), "stockId must not be null");
        Long amount = Objects.requireNonNull(req.getAmount(), "amount must not be null");
        CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType type =
                Objects.requireNonNull(req.getType(), "type must not be null");

        switch (type) {
            case STOCK_BUY -> updatePortfolioCacheByStockBuy(portfolioId, stockId, amount);
            case STOCK_SELL -> updatePortfolioCacheByStockSell(portfolioId, stockId, amount);
        }
    }

    @Override
    public void updateUserCache(CacheUpdateDto.UserCacheUpdateRequest request) {
        Long userId = Objects.requireNonNull(request.getUserId(), "userId must not be null");
        Long portfolioId = Objects.requireNonNull(request.getPortfolioId(), "portfolioId must not be null");
        CacheUpdateDto.UserCacheUpdateRequest.ChangeType type =
                Objects.requireNonNull(request.getType(), "type must not be null");

        Long portfolioPurchasedValue = portfolioStateStore.findPurchasedValue(portfolioId);

        switch (type) {
            case CREATED -> updateUserCacheByPortfolioCreated(userId, portfolioId, portfolioPurchasedValue);
            case DELETED -> updateUserCacheByPortfolioDeleted(userId, portfolioId, portfolioPurchasedValue);
        }
    }

    private void updatePortfolioCacheByStockBuy(Long portfolioId, Long stockId, Long amount) {
        boolean added = portfolioStateStore.addPortfolioStock(stockId, portfolioId);
        portfolioStateStore.addPurchasedValue(portfolioId, amount);

        if (added) {
            portfolioStateStore.increaseAssetCount(portfolioId);
        }
    }

    private void updatePortfolioCacheByStockSell(Long portfolioId, Long stockId, Long amount) {
        boolean removed = portfolioStateStore.removePortfolioStock(stockId, portfolioId);
        portfolioStateStore.subtractPurchasedValue(portfolioId, amount);

        if (removed) {
            portfolioStateStore.decreaseAssetCount(portfolioId);
        }
    }

    private void updateUserCacheByPortfolioCreated(Long userId, Long portfolioId, Long portfolioPurchasedValue) {
        userStateStore.mapPortfolioToUser(portfolioId, userId);
        userStateStore.addPurchasedValue(userId, portfolioPurchasedValue);
        userStateStore.increasePortfolioCount(userId);
    }

    private void updateUserCacheByPortfolioDeleted(Long userId, Long portfolioId, Long portfolioPurchasedValue) {
        userStateStore.removePortfolioUserMapping(portfolioId);
        userStateStore.subtractPurchasedValue(userId, portfolioPurchasedValue);
        userStateStore.decreasePortfolioCount(userId);
    }
}
