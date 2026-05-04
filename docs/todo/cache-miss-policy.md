# Cache Miss Policy TODO

가격 업데이트 이벤트는 Redis 캐시가 먼저 준비되어 있다는 전제에서 동작한다.
따라서 cache miss 시 어떤 경우를 no-op으로 볼지, 어떤 경우를 retry/DLT 대상으로 볼지 명확히 정의해야 한다.

## 현재 전제

가격 이벤트 하나에는 보통 아래 정보만 포함된다.

```text
stockId
newPrice
```

이 이벤트만으로는 포트폴리오/유저 수익률을 완전히 계산할 수 없다.
가격 이벤트 처리 전에 아래 캐시 데이터가 준비되어 있어야 한다.

```text
profit:stock:{stockId}:portfolios
profit:portfolio-user:{portfolioId}
profit:portfolio:{portfolioId}
profit:portfolio:{portfolioId}:stock:{stockId}
profit:user:{userId}
```

## 필수 데이터

종목별 영향 포트폴리오 조회:

```text
Key: profit:stock:{stockId}:portfolios
Type: Set
Value: portfolioId 목록
```

포트폴리오 소유 유저 조회:

```text
Key: profit:portfolio-user:{portfolioId}
Type: String
Value: userId
```

포트폴리오 내 종목 보유 상태:

```text
Key: profit:portfolio:{portfolioId}:stock:{stockId}
Type: Hash
Required fields:
- quantity
- averagePurchasePrice
Expected fields:
- purchaseAmount
- currentPrice
- unrealizedProfit
- returnRate
```

포트폴리오 집계:

```text
Key: profit:portfolio:{portfolioId}
Type: Hash
Required fields:
- totalPurchaseAmount
Expected fields:
- unrealizedProfit
- returnRate
- realizedProfit
- totalStockCount
```

유저 집계:

```text
Key: profit:user:{userId}
Type: Hash
Required fields:
- totalPurchaseAmount
Expected fields:
- unrealizedProfit
- returnRate
- realizedProfit
- totalPortfolioCount
```

유저 랭킹:

```text
Key: profit:user:return-rate-ranking
Type: ZSet
Score: user.returnRate
Member: userId
```

랭킹 ZSet은 없어도 `ZADD`로 자동 생성 가능하다.

## Cache Miss 분류

cache miss는 한 종류로 처리하지 않는다.
아래 세 가지로 분리한다.

### 1. 정상 Empty

해당 종목을 실제로 아무 포트폴리오도 보유하지 않는 경우다.

```text
stockId를 보유한 portfolioIds가 진짜 없음
```

처리:

```text
no-op
```

주의점:

```text
Redis Set key 없음
```

만으로는 정상 empty인지 cache miss인지 구분할 수 없다.
따라서 initialized marker가 필요하다.

예:

```text
profit:stock:{stockId}:portfolios
profit:stock:{stockId}:portfolios:initialized = true
```

판단 기준:

```text
Set empty + initialized marker exists
-> 정상 empty, no-op

Set empty + initialized marker missing
-> cache miss
```

### 2. 복구 가능한 Cache Miss

계산에 필요한 캐시가 아직 준비되지 않았거나 사라진 경우다.

예:

```text
portfolio-user mapping 없음
portfolio-stock holding 없음
portfolio aggregate 없음
user aggregate 없음
```

처리:

```text
1. cache hydration/rebuild 요청
2. 현재 이벤트 실패 처리
3. Kafka retry 또는 DLT 유도
```

가격 이벤트 워커가 조용히 skip하면 안 된다.
그 경우 수익률 갱신 누락이 감춰진다.

### 3. 데이터 불일치 또는 손상

키는 존재하지만 값이 계산 불가능한 경우다.

예:

```text
quantity <= 0
averagePurchasePrice <= 0
totalPurchaseAmount <= 0
portfolioId는 있는데 userId 매핑 없음
```

처리:

```text
1. corrupted 상태로 분류
2. error log 기록
3. metric 증가
4. DLT 또는 운영 알림
5. 별도 보정 job으로 복구
```

## 권장 동작표

| 상황 | 권장 처리 |
| --- | --- |
| `stock portfolio mapping` key 없음 + initialized marker 없음 | `CacheMissException` |
| `stock portfolio mapping` empty + initialized marker 있음 | no-op |
| `portfolio-stock holding` 없음 | `CacheMissException` |
| `holding.quantity` 없음 | `CacheMissException` 또는 `CacheCorruptedException` |
| `holding.quantity <= 0` | `CacheCorruptedException` |
| `holding.averagePurchasePrice` 없음 | `CacheMissException` 또는 `CacheCorruptedException` |
| `holding.averagePurchasePrice <= 0` | `CacheCorruptedException` |
| `portfolio aggregate` 없음 | `CacheMissException` |
| `portfolio.totalPurchaseAmount` 없음 | `CacheMissException` |
| `portfolio.totalPurchaseAmount <= 0` | `CacheCorruptedException` |
| `portfolio-user mapping` 없음 | `CacheMissException` 또는 `CacheCorruptedException` |
| `user aggregate` 없음 | `CacheMissException` |
| `user.totalPurchaseAmount` 없음 | `CacheMissException` |
| `user.totalPurchaseAmount <= 0` | `CacheCorruptedException` |
| ranking ZSet 없음 | self-heal with `ZADD` |

## 예외 타입 TODO

cache miss와 데이터 손상을 구분하는 예외 타입을 추가한다.

```java
public class ProfitCacheMissException extends RuntimeException {
}
```

```java
public class ProfitCacheCorruptedException extends RuntimeException {
}
```

Kafka error handler는 두 예외를 다르게 처리할 수 있어야 한다.

```text
ProfitCacheMissException
-> retry 가능
-> cache rebuild 요청 후 재처리 가능

ProfitCacheCorruptedException
-> retry만으로 복구 어려움
-> DLT/알림 우선
```

## Cache Hydration TODO

가격 이벤트 워커는 가격 업데이트 처리에 집중한다.
cache miss 복구를 위해 Monolith API를 즉시 호출하는 read-through 방식은 초기 구현에서는 피한다.

권장 방향:

```text
1. cache rebuild 요청 이벤트 발행
2. 현재 가격 이벤트는 실패 처리
3. Kafka retry 또는 DLT에서 재처리
```

예상 이벤트:

```text
profit-cache-rebuild-requested.v1
```

필드 예:

```text
stockId
portfolioId nullable
userId nullable
reason
occurredAt
```

## 구현 TODO

- `stockId -> portfolioIds` 조회 시 initialized marker 확인 추가
- `ProfitCacheMissException` 추가
- `ProfitCacheCorruptedException` 추가
- Lua Script error message를 Java 예외 타입으로 매핑
- cache rebuild 요청 producer 추가 여부 결정
- Kafka error handler에서 retry/DLT 정책 분리
- cache miss/corrupted metric 추가
- cache miss 발생 시 로그에 `stockId`, `portfolioId`, `userId`, `reason` 포함

## 최종 정책

```text
정상 empty는 no-op
계산에 필요한 cache miss는 실패 처리 + hydration 요청
잘못된 값은 corrupted로 보고 DLT/알림
ranking ZSet은 self-heal
```
