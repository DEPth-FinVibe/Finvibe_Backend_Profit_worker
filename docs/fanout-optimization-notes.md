# Profit Worker Fanout 최적화 검토

이 문서는 주가 변경 이벤트 처리에서 비용이 큰 것으로 관측된 `bulk_prefetch`, `portfolio_fanout`, `user_fanout` 페이즈의 현재 동작과 최적화 후보를 정리한다.

## 전제

- Redis는 단순 캐시가 아니라 profit worker의 계산 상태 저장소다.
- Kafka listener는 batch mode로 동작하며, 기본 `max.poll.records`는 50이다.
- 주가 변경 이벤트는 batch 내에서 같은 `stockId`의 최신 이벤트만 남긴 뒤 처리한다.
- 현재 가격 변경 재계산 경로는 이미 상당 부분 Redis pipeline을 사용한다.
- 여러 worker instance 또는 listener concurrency가 존재할 수 있으므로 같은 key에 대한 순서와 원자성을 유지해야 한다.

## 현재 처리 흐름

`StockPriceEventConsumer`는 Kafka batch를 받아 `stockId` 기준으로 중복을 제거한 뒤 `ProfitCalculateService.updateProfitsByStockPriceChanges()`를 호출한다.

현재 `ProfitCalculateService`의 흐름은 다음과 같다.

1. `stockId -> portfolioIds` 역인덱스 조회
2. `bulk_prefetch`
3. JVM 메모리에서 portfolio delta 계산
4. Redis에 portfolio current value 증분 반영
5. 종목별 current value 저장
6. `portfolio_fanout`
7. `user_fanout`

## bulk_prefetch

목적은 delta 계산과 valuation snapshot 생성에 필요한 Redis 상태를 미리 가져오는 것이다.

현재 조회 대상:

- 포트폴리오 메타데이터
  - `pv`: purchased value
  - `ac`: asset count
  - `u`: owner user id
  - `cvp`: precise current value
  - `cv`: rounded/current snapshot value fallback
- 포트폴리오-종목 보유 상태
  - `quantity`
  - 종목별 기존 `current-value`

현재 비용 특성:

- `bulkFetchPortfolioMetadata(portfolioIds)`와 `bulkFetchStockHoldings(holdingKeys)`는 서로 독립이지만 순차 실행된다.
- delta 계산에 실제로 필요한 값은 `quantity`와 종목별 기존 `current-value`다.
- `pv`, `ac`, `u`는 `portfolio_fanout`에서 valuation snapshot을 만들 때 필요하다.
- 따라서 현재 구조는 metadata를 조금 이른 시점에 가져오며, 이후 portfolio current value increment와 별도 Redis round-trip을 만든다.

## portfolio_fanout

목적은 영향받은 포트폴리오별 valuation snapshot을 생성하고 dirty 대상으로 표시하는 것이다.

현재 동작:

1. `portfolioDeltaSum`의 각 `portfolioId`를 순회한다.
2. `bulk_prefetch`에서 가져온 metadata를 읽는다.
3. `bulkIncrementCurrentValues()` 결과로 받은 새 portfolio current value를 사용한다.
4. `purchasedValue`, `currentValue`, `assetCount`로 `profitRate`를 계산한다.
5. `PortfolioValuation`을 생성한다.
6. metadata의 `userId`가 있으면 `userDeltaByUserId`에 delta를 누적한다.
7. `bulkSavePortfolioValuations()`로 Redis snapshot을 pipeline 저장한다.

현재 비용 특성:

- 영향받은 포트폴리오 수만큼 snapshot 객체가 생성된다.
- Redis hash에 `pv`, `cv`, `pr`, `ac`, `del`, `ua`를 쓰고 dirty set에 추가한다.
- 가격 변경 이벤트에서는 `pv`, `ac`, `del`은 대부분 변하지 않는데도 snapshot 저장 시 함께 다시 쓴다.
- 주가 이벤트가 자주 들어오면 같은 portfolio가 짧은 시간에 여러 번 snapshot write될 수 있다.

## user_fanout

목적은 portfolio delta를 user 단위로 합산해 user valuation snapshot을 갱신하는 것이다.

현재 동작:

1. `portfolio_fanout`에서 만든 `userDeltaByUserId`를 받는다.
2. `bulkIncrementCurrentValues(userDeltaByUserId)`로 `usr:{userId}`의 `cvp`를 `HINCRBYFLOAT` 한다.
3. `bulkFetchUserMetadata(userIds)`로 `pv`, `pc`를 pipeline 조회한다.
4. JVM에서 `UserValuation`을 생성하고 `profitRate`를 계산한다.
5. `bulkSaveUserValuations()`로 Redis snapshot을 pipeline 저장한다.

현재 비용 특성:

- affected user 수가 커지면 Redis pipeline이 최소 세 번 발생한다.
- `HINCRBYFLOAT`, metadata read, snapshot write가 각각 별도 phase다.
- user snapshot 역시 같은 user가 짧은 시간에 반복 저장될 수 있다.

## 최적화 후보

### 1. user current value increment와 metadata fetch 병합

현재 `user_fanout`은 다음처럼 세 단계다.

```text
bulkIncrementCurrentValues(user)
bulkFetchUserMetadata(user)
bulkSaveUserValuations(user)
```

앞의 두 단계는 하나의 pipeline으로 합칠 수 있다.

```text
for each user:
  HINCRBYFLOAT usr:{userId} cvp delta
  HMGET usr:{userId} pv pc
```

기대 효과:

- `user_fanout`의 Redis round-trip을 하나 줄인다.
- `HINCRBYFLOAT` 이후 같은 pipeline에서 metadata를 읽으므로 갱신 후 current value와 metadata를 함께 사용해 snapshot을 만들 수 있다.

주의점:

- Spring Data Redis pipeline 결과 순서를 정확히 검증해야 한다.
- 전용 결과 DTO가 필요하다. 예: `UserCurrentValueAndMetadata`.

우선순위: 높음.

### 2. portfolio current value increment와 metadata fetch 병합

현재 `bulk_prefetch`에서 portfolio metadata를 먼저 가져오고, 이후 별도 phase에서 portfolio `cvp`를 increment한다.

개선 방향:

```text
현재:
1. bulkFetchPortfolioMetadata(portfolioIds)
2. bulkFetchStockHoldings(holdingKeys)
3. delta 계산
4. bulkIncrementCurrentValues(portfolioDeltaSum)

개선:
1. bulkFetchStockHoldings(holdingKeys)
2. delta 계산
3. for each portfolio:
     HINCRBYFLOAT pf:{portfolioId} cvp delta
     HMGET pf:{portfolioId} pv ac u
```

기대 효과:

- `bulk_prefetch`에서 portfolio metadata 조회 비용을 제거한다.
- portfolio current value increment와 snapshot 생성용 metadata read를 하나의 pipeline으로 합친다.

주의점:

- delta 계산에는 metadata가 필요하지 않다는 전제가 유지되어야 한다.
- `portfolioDeltaSum`에 포함되지 않은 portfolio는 valuation snapshot 대상에서 제외된다.
- 같은 portfolio에 대해 여러 stock delta가 있으면 반드시 합산 후 한 번만 increment해야 한다.

우선순위: 높음.

### 3. 가격 변경용 snapshot 저장 필드 축소

가격 변경 이벤트에서 변하는 값은 주로 `cv`, `pr`, `ua`다.

현재 portfolio snapshot 저장은 `pv`, `cv`, `pr`, `ac`, `del`, `ua`를 모두 다시 쓴다.
user snapshot 저장도 `pv`, `cv`, `pr`, `pc`, `ua`를 모두 다시 쓴다.

개선 방향:

- 가격 변경 경로용 저장 메서드를 분리한다.
- portfolio 가격 변경 snapshot은 `cv`, `pr`, `ua`와 dirty marker만 갱신한다.
- user 가격 변경 snapshot도 `cv`, `pr`, `ua`와 dirty marker만 갱신한다.
- `pv`, `ac`, `pc`, `del`은 거래/포트폴리오 변경 이벤트에서만 갱신한다.

기대 효과:

- Redis write payload 감소.
- pipeline 응답 처리와 serialization 비용 감소.

주의점:

- write-back batch가 full snapshot을 기대한다면 Redis hash에는 기존 `pv`, `ac`, `pc` 값이 남아 있어야 한다.
- 포트폴리오 삭제 marker와 일반 valuation 저장이 충돌하지 않도록 삭제 상태를 우선해야 한다.

우선순위: 중간.

### 4. fanout snapshot coalescing

주가 이벤트가 자주 들어오는 경우 같은 portfolio/user가 짧은 시간에 여러 번 valuation snapshot으로 저장된다.

개선 방향:

- 가격 이벤트 처리 시 Redis 계산 상태(`pf:{id}.cvp`, `usr:{id}.cvp`)는 즉시 갱신한다.
- dirty marker도 즉시 추가한다.
- valuation snapshot 저장은 짧은 window로 합친다.
- 예: 100ms 또는 500ms 동안 같은 portfolio/user는 마지막 상태만 snapshot 저장한다.

기대 효과:

- 고빈도 가격 이벤트에서 snapshot write fanout이 크게 줄 수 있다.
- Redis와 write-back batch의 dirty 처리 부담이 줄어든다.

주의점:

- DB write-back에 허용 가능한 지연 시간이 필요하다.
- worker 장애 시 coalescing buffer의 유실 가능성을 고려해야 한다.
- Redis 계산 상태가 최신이면 dirty marker만으로도 복구 가능하도록 batch 계약을 정리해야 한다.

우선순위: 중간. 트래픽 패턴이 고빈도라면 높음.

### 5. write-back batch로 valuation 계산 일부 이동

더 큰 구조 변경으로, price worker는 계산 상태와 dirty marker만 갱신하고 batch writer가 snapshot을 읽어 DB valuation을 계산할 수 있다.

개선 방향:

- worker critical path:
  - `pf:{id}.cvp`, `usr:{id}.cvp` 갱신
  - dirty set 추가
- batch writer:
  - dirty 대상의 Redis hash를 읽음
  - `pv`, `cvp`, `ac`, `pc` 기반으로 `profitRate` 계산
  - DB upsert

기대 효과:

- worker의 `portfolio_fanout`, `user_fanout` snapshot write 비용이 크게 줄어든다.

주의점:

- batch service 책임과 구현 복잡도가 커진다.
- 현재 write-back 계약 변경이 필요하다.
- batch writer가 더 많은 Redis read를 수행하므로 병목이 worker에서 batch로 이동할 수 있다.

우선순위: 낮음에서 중간. 단기 최적화보다는 구조 개선안이다.

### 6. bounded virtual thread chunk 병렬화

이미 pipeline으로 묶인 command를 command 단위로 virtual thread에 분산하는 것은 적합하지 않다.
다만 매우 큰 fanout을 일정 크기의 chunk로 나누고, chunk pipeline을 제한된 동시성으로 실행하는 방식은 실험 가치가 있다.

개선 방향:

```text
portfolio chunk size: 1,000 ~ 5,000
concurrency: 2 ~ 4부터 측정

chunk 1 pipeline
chunk 2 pipeline
chunk 3 pipeline
chunk 4 pipeline
```

적용 후보:

- `bulkFetchStockHoldings`
- portfolio increment + metadata fetch 병합 pipeline
- user increment + metadata fetch 병합 pipeline
- snapshot save pipeline

주의점:

- 같은 key가 여러 chunk에 중복되지 않게 해야 한다.
- 같은 `stockId` 가격 이벤트가 동시에 처리되어 같은 old stock current value를 읽는 상황을 막아야 한다.
- Redis connection pool, Redis server CPU, client-side response parsing이 병목이 될 수 있다.
- concurrency를 크게 잡으면 평균 latency는 줄어도 p95/p99와 Redis 부하가 악화될 수 있다.

우선순위: 중간. pipeline 병합 이후 측정 기반으로 적용한다.

## 권장 적용 순서

1. `user_fanout`에서 user current value increment와 metadata fetch를 단일 pipeline으로 병합한다.
2. `bulk_prefetch`에서 portfolio metadata 조회를 제거하고, portfolio current value increment와 metadata fetch를 단일 pipeline으로 병합한다.
3. 가격 변경 경로의 snapshot 저장 필드를 줄인다.
4. 필요하면 fanout snapshot coalescing을 도입한다.
5. 큰 fanout batch에서만 bounded virtual thread chunk 병렬화를 실험한다.
6. 그래도 worker critical path가 부담이면 write-back batch로 valuation 계산 일부 이동을 검토한다.

## 측정 지표

최적화 전후로 다음 지표를 비교한다.

- `profit.worker.phase.duration`
  - `bulk_prefetch`
  - `portfolio_fanout`
  - `user_fanout`
  - `pipeline_portfolio_incr`
  - `pipeline_stock_cv_set`
- `profit.worker.redis.command.duration`
  - `pipeline_hmget_portfolio`
  - `pipeline_get_stock_holdings`
  - `pipeline_hincrbyfloat_portfolio_cv`
  - `pipeline_hincrbyfloat_user_cv`
  - `pipeline_hmget_user`
  - `pipeline_save_portfolio_valuations`
  - `pipeline_save_user_valuations`
- Kafka consumer lag
- Redis CPU 사용률
- Redis network in/out
- Redis connected clients와 blocked clients
- worker p95/p99 listener duration
- dirty set backlog

## 정합성 체크포인트

최적화 후 다음 조건은 반드시 유지되어야 한다.

- 같은 `(portfolioId, stockId)`의 `quantity`와 종목별 `current-value` 업데이트 순서가 깨지지 않는다.
- 같은 portfolio의 여러 stock delta는 portfolio `cvp`에 반영하기 전에 합산된다.
- user delta는 affected portfolio delta를 user별로 합산한 뒤 한 번만 반영된다.
- portfolio 삭제 marker가 일반 valuation snapshot에 의해 되살아나지 않는다.
- Redis pipeline 결과 개수가 예상과 다르면 실패 처리한다.
- write-back batch가 읽는 필수 필드는 Redis hash에 항상 남아 있다.

