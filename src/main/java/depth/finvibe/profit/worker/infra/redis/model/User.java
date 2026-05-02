package depth.finvibe.profit.worker.infra.redis.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

@RedisHash(value = "profit-user")
public class User {
    @Id
    private Long id;

    // 포트폴리오 총 개수
    private int totalPortfolioCount;

    // 평균 수익률
    private Double returnRate;

    // 실현 손익
    private Double realizedProfit;
}
