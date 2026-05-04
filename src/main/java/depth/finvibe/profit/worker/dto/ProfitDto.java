package depth.finvibe.profit.worker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

public class ProfitDto {

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    @Builder
    public static class ProfitRecalculateRequest {
        private Long stockId;
        private Double newPrice;
        private Instant timestamp;
    }
}
