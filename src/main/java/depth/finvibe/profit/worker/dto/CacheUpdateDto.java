package depth.finvibe.profit.worker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class CacheUpdateDto {

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    @Builder
    public static class PortfolioCacheUpdateRequest {
        private Long portfolioId;

        private Long stockId;

        private TradeType type;

        private Long amount; // 원화 단위

        public enum TradeType {
            STOCK_BUY,
            STOCK_SELL
        }
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    @Builder
    public static class UserCacheUpdateRequest {
        private Long userId;

        private Long portfolioId;

        private ChangeType type;

        public enum ChangeType {
            CREATED,
            DELETED
        }
    }
}
