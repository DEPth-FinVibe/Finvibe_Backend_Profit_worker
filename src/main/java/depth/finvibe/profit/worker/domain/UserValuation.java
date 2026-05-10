package depth.finvibe.profit.worker.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
public class UserValuation {
    @Id
    private Long userId;

    private Long purchasedValue; // 구매액

    private Long currentValue; // 평가액

    private Double profitRate; // 수익률

    private Long portfolioCount; // 포트폴리오 개수
}
