package depth.finvibe.profit.worker.consumer.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import depth.finvibe.profit.worker.dto.ProfitDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockPriceUpdatedEvent {
    private Long stockId;

    @JsonAlias({"price", "currentPrice"})
    private Double newPrice;

    private Instant timestamp;

    public ProfitDto.ProfitRecalculateRequest toProfitRecalculateRequest() {
        return ProfitDto.ProfitRecalculateRequest.builder()
                .stockId(stockId)
                .newPrice(newPrice)
                .timestamp(timestamp)
                .build();
    }
}
