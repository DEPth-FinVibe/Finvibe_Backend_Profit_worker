package depth.finvibe.profit.worker.consumer.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockPriceUpdatedEvent {
	private Long stockId;
	private BigDecimal price;
	private LocalDateTime updatedAt;
}
