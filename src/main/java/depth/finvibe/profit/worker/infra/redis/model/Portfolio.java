package depth.finvibe.profit.worker.infra.redis.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

@RedisHash(value = "profit-portfolio")
public class Portfolio {
    @Id
    private Long id;

    // 전체 종목 수
    private int totalStockCount;

    // 평균 수익률
    private Double returnRate;

    // 실현 손익
    private Double realizedProfit;
}
