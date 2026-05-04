package depth.finvibe.profit.worker.infra.redis.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RedisHash(value = "profit:portfolio")
public class Portfolio {
    @Id
    private Long id;

    // 전체 종목 수
    private int totalStockCount;

    // 평균 수익률
    private Double returnRate;

    // 총 매입 금액
    private Double totalPurchaseAmount;

    // 평가 손익
    private Double unrealizedProfit;

    // 실현 손익
    private Double realizedProfit;
}
