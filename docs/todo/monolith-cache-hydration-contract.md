# Monolith Cache Hydration Contract

cache miss 발생 시 profit worker는 miss된 캐시 종류에 맞는 Monolith API를 호출한다.
한 번에 모든 데이터를 요청하지 않고, 필요한 캐시 단위로 데이터를 요청한다.

## 전체 원칙

```text
1. cache miss reason을 식별한다.
2. reason에 맞는 Monolith API만 호출한다.
3. 응답 데이터를 해당 Redis key에만 반영한다.
4. 현재 가격 이벤트는 1회 재처리한다.
5. 재처리 후에도 cache miss가 발생하면 Kafka retry/DLT 정책에 위임한다.
```

## Cache Miss별 요청 데이터

| Cache miss reason | Monolith API | Monolith에 요구하는 데이터 | Redis 반영 대상 |
| --- | --- | --- | --- |
| `STOCK_PORTFOLIO_MAPPING_MISSING` | `GET /internal/profit-cache/stocks/{stockId}/portfolios` | `stockId`, `initialized`, `portfolioIds` | `profit:stock:{stockId}:portfolios`, `profit:stock:{stockId}:portfolios:initialized` |
| `PORTFOLIO_OWNER_MISSING` | `GET /internal/profit-cache/portfolios/{portfolioId}/owner` | `portfolioId`, `userId` | `profit:portfolio-user:{portfolioId}` |
| `PORTFOLIO_HOLDING_MISSING` | `GET /internal/profit-cache/portfolios/{portfolioId}/stocks/{stockId}/holding` | `portfolioId`, `stockId`, `quantity`, `averagePurchasePrice`, `purchaseAmount`, `currentPrice`, `unrealizedProfit`, `returnRate` | `profit:portfolio:{portfolioId}:stock:{stockId}` |
| `PORTFOLIO_AGGREGATE_MISSING` | `GET /internal/profit-cache/portfolios/{portfolioId}/aggregate` | `portfolioId`, `totalPurchaseAmount`, `unrealizedProfit`, `returnRate`, `realizedProfit`, `totalStockCount` | `profit:portfolio:{portfolioId}` |
| `USER_AGGREGATE_MISSING` | `GET /internal/profit-cache/users/{userId}/aggregate` | `userId`, `totalPurchaseAmount`, `unrealizedProfit`, `returnRate`, `realizedProfit`, `totalPortfolioCount` | `profit:user:{userId}` |

## 1. Stock Portfolio Mapping Miss

종목을 보유한 포트폴리오 목록 캐시가 없거나, 빈 set이지만 initialized marker도 없는 경우다.

### 요청

```http
GET /internal/profit-cache/stocks/{stockId}/portfolios
```

### 응답 데이터

```json
{
  "stockId": 1,
  "initialized": true,
  "portfolioIds": [10, 11]
}
```

### 필드 의미

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `stockId` | yes | 가격이 변경된 종목 ID |
| `initialized` | yes | Monolith 기준으로 해당 종목 보유 포트폴리오 조회가 완료되었는지 여부 |
| `portfolioIds` | yes | 해당 종목을 보유한 포트폴리오 ID 목록 |

### 처리

```text
portfolioIds가 있으면:
-> profit:stock:{stockId}:portfolios set 저장
-> profit:stock:{stockId}:portfolios:initialized = true 저장

portfolioIds가 비어 있고 initialized=true이면:
-> profit:stock:{stockId}:portfolios:initialized = true 저장
-> 이후 동일 종목 이벤트는 정상 empty로 no-op 가능
```

## 2. Portfolio Owner Miss

포트폴리오 소유 유저 매핑이 없는 경우다.

### 요청

```http
GET /internal/profit-cache/portfolios/{portfolioId}/owner
```

### 응답 데이터

```json
{
  "portfolioId": 10,
  "userId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### 필드 의미

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `portfolioId` | yes | 포트폴리오 ID |
| `userId` | yes | 포트폴리오 소유 유저 ID. Monolith의 UUID를 문자열로 전달한다. |

### 처리

```text
profit:portfolio-user:{portfolioId} = userId
```

## 3. Portfolio Holding Miss

포트폴리오 내 특정 종목 보유 상태 캐시가 없는 경우다.

### 요청

```http
GET /internal/profit-cache/portfolios/{portfolioId}/stocks/{stockId}/holding
```

### 응답 데이터

```json
{
  "portfolioId": 10,
  "stockId": 1,
  "quantity": 10,
  "averagePurchasePrice": 90000,
  "purchaseAmount": 900000,
  "currentPrice": 95000,
  "unrealizedProfit": 50000,
  "returnRate": 0.0555555556
}
```

### 필드 의미

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `portfolioId` | yes | 포트폴리오 ID |
| `stockId` | yes | 종목 ID |
| `quantity` | yes | 보유 수량. Redis 계산에는 양수여야 한다. |
| `averagePurchasePrice` | yes | 평균 매입가. Redis 계산에는 양수여야 한다. |
| `purchaseAmount` | yes | 해당 종목 총 매입 금액 |
| `currentPrice` | expected | 현재가. 없으면 다음 가격 이벤트에서 갱신될 수 있지만, cache 복구 응답에는 포함하는 것을 권장한다. |
| `unrealizedProfit` | expected | 해당 종목 평가 손익 |
| `returnRate` | expected | 해당 종목 수익률 |

### Monolith 도메인 매핑

```text
quantity <- Asset.amount
purchaseAmount <- Asset.totalPrice.amount
averagePurchasePrice <- Asset.totalPrice.amount / Asset.amount
currentPrice <- 별도 현재가 또는 Asset.valuation.currentValue / Asset.amount
unrealizedProfit <- Asset.valuation.profitLoss
returnRate <- Asset.valuation.returnRate
```

`currentPrice`는 valuation에서 역산하지 말고 Monolith가 명시적으로 제공하는 것을 권장한다.

### 처리

```text
profit:portfolio:{portfolioId}:stock:{stockId}
-> quantity
-> averagePurchasePrice
-> purchaseAmount
-> currentPrice
-> unrealizedProfit
-> returnRate
```

## 4. Portfolio Aggregate Miss

포트폴리오 집계 캐시가 없는 경우다.

### 요청

```http
GET /internal/profit-cache/portfolios/{portfolioId}/aggregate
```

### 응답 데이터

```json
{
  "portfolioId": 10,
  "totalPurchaseAmount": 1000000,
  "unrealizedProfit": 50000,
  "returnRate": 0.05,
  "realizedProfit": 0,
  "totalStockCount": 3
}
```

### 필드 의미

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `portfolioId` | yes | 포트폴리오 ID |
| `totalPurchaseAmount` | yes | 포트폴리오 전체 매입 금액. Redis 계산에는 양수여야 한다. |
| `unrealizedProfit` | expected | 포트폴리오 평가 손익 |
| `returnRate` | expected | 포트폴리오 수익률 |
| `realizedProfit` | expected | 포트폴리오 실현 손익 |
| `totalStockCount` | expected | 포트폴리오 내 보유 종목 수 |

### Monolith 도메인 매핑

```text
totalPurchaseAmount <- PortfolioGroup.assets[*].totalPrice.amount 합계
unrealizedProfit <- PortfolioGroup.valuation.totalProfitLoss
returnRate <- PortfolioGroup.valuation.totalReturnRate
totalStockCount <- PortfolioGroup.assets.size
```

`realizedProfit`은 현재 제공 도메인에서 명확하지 않다.
Monolith가 별도 실현 손익 모델을 갖고 있지 않다면 `0`으로 내려주거나, 해당 필드를 nullable/미지원으로 명확히 해야 한다.

### 처리

```text
profit:portfolio:{portfolioId}
-> totalPurchaseAmount
-> unrealizedProfit
-> returnRate
-> realizedProfit
-> totalStockCount
```

## 5. User Aggregate Miss

유저 집계 캐시가 없는 경우다.

### 요청

```http
GET /internal/profit-cache/users/{userId}/aggregate
```

### 응답 데이터

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "totalPurchaseAmount": 3000000,
  "unrealizedProfit": 100000,
  "returnRate": 0.0333333333,
  "realizedProfit": 0,
  "totalPortfolioCount": 2
}
```

### 필드 의미

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `userId` | yes | 유저 ID. Monolith의 UUID를 문자열로 전달한다. |
| `totalPurchaseAmount` | yes | 유저 전체 포트폴리오의 총 매입 금액. Redis 계산에는 양수여야 한다. |
| `unrealizedProfit` | expected | 유저 전체 평가 손익 |
| `returnRate` | expected | 유저 전체 수익률 |
| `realizedProfit` | expected | 유저 전체 실현 손익 |
| `totalPortfolioCount` | expected | 유저의 전체 포트폴리오 수 |

### Monolith 도메인 매핑

```text
totalPurchaseAmount <- 유저의 PortfolioGroup 전체 assets[*].totalPrice.amount 합계
unrealizedProfit <- 유저의 PortfolioGroup.valuation.totalProfitLoss 합계
returnRate <- unrealizedProfit / totalPurchaseAmount
totalPortfolioCount <- 유저의 PortfolioGroup 개수
```

`realizedProfit`은 portfolio aggregate와 동일하게 Monolith의 실현 손익 데이터 출처가 필요하다.

### 처리

```text
profit:user:{userId}
-> totalPurchaseAmount
-> unrealizedProfit
-> returnRate
-> realizedProfit
-> totalPortfolioCount
```

ranking ZSet은 이 응답으로 직접 복구하지 않는다.
가격 이벤트 재처리 중 user profit update Lua script가 `ZADD`로 self-heal 한다.

## Monolith가 명확히 해야 하는 데이터

아래 항목은 Monolith API 계약에서 명확히 정의해야 한다.

| 항목 | 필요한 이유 |
| --- | --- |
| `userId` 타입 | Monolith는 UUID를 사용한다. worker Redis key/member도 문자열 UUID로 통일하는 것이 안전하다. |
| `currentPrice` 제공 방식 | `AssetValuation.currentValue / amount`로 역산하면 정밀도 문제가 생길 수 있다. 가능하면 명시 제공한다. |
| `realizedProfit` 출처 | 현재 전달된 `Asset`, `PortfolioGroup` 도메인만으로는 실현 손익 출처가 명확하지 않다. |
| valuation 없는 asset 처리 | valuation이 없는 asset도 hydration 대상인지, 아니면 Monolith가 현재가로 즉시 계산해서 내려줄지 정해야 한다. |
| 숫자 정밀도 | Monolith는 `BigDecimal` 기반이다. JSON number 또는 string 중 어떤 포맷으로 내려줄지 정해야 한다. |

## 현재 구현 기준 DTO

```text
StockPortfolioMappingData
PortfolioOwnerData
PortfolioHoldingData
PortfolioAggregateData
UserAggregateData
```

각 DTO는 Monolith API 응답을 그대로 받는 용도이며, Redis write는 `ProfitCacheWriter` 구현체가 담당한다.
