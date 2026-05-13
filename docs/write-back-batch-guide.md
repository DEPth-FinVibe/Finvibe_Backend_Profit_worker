# Write-back Batch 구현 가이드

profit worker는 Redis를 계산용 상태 저장소로 사용하고, DB 반영은 외부 batch service가 담당한다.
이 문서는 dirty valuation을 DB에 반영하는 batch service 구현 기준을 정리한다.

## 책임

write-back batch service의 책임은 다음과 같다.

| 책임 | 설명 |
| --- | --- |
| dirty 대상 조회 | Redis에 표시된 포트폴리오/유저 dirty 대상을 조회한다. |
| snapshot 읽기 | dirty 대상의 valuation snapshot을 Redis에서 읽는다. |
| DB upsert | portfolio valuation, user valuation 테이블에 idempotent upsert 한다. |
| dirty 제거 | DB 반영 성공 후 dirty 대상에서 제거한다. |
| 실패 재처리 | DB 반영 실패 시 dirty 대상을 유지하거나 재등록한다. |

## 처리 흐름

1. portfolio dirty 대상과 user dirty 대상을 일정 개수만큼 가져온다.
2. 각 대상의 Redis valuation snapshot을 읽는다.
3. snapshot이 없거나 필수 필드가 누락된 대상은 skip하거나 재동기화 대상으로 기록한다.
4. DB에 upsert 한다.
5. DB 반영에 성공한 대상만 dirty 목록에서 제거한다.
6. 실패한 대상은 dirty 목록에 남겨 다음 batch에서 재시도한다.

## Portfolio Valuation Snapshot

batch service는 포트폴리오 dirty 대상에 대해 다음 값을 읽어 DB에 반영한다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| portfolioId | Long | 포트폴리오 ID |
| purchasedValue | Long | 총 구매액 |
| currentValue | Long | 현재 총 평가액 |
| profitRate | Double | 수익률 |
| assetCount | Long | 보유 종목 수 |
| updatedAt | Instant | Redis snapshot 갱신 시각 |

## User Valuation Snapshot

batch service는 유저 dirty 대상에 대해 다음 값을 읽어 DB에 반영한다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| userId | Long | 유저 ID |
| purchasedValue | Long | 유저 전체 총 구매액 |
| currentValue | Long | 유저 전체 현재 평가액 |
| profitRate | Double | 수익률 |
| portfolioCount | Long | 보유 포트폴리오 수 |
| updatedAt | Instant | Redis snapshot 갱신 시각 |

## Upsert 기준

DB write-back은 idempotent 해야 한다.

권장 기준:

- portfolio valuation은 `portfolioId` 기준으로 upsert 한다.
- user valuation은 `userId` 기준으로 upsert 한다.
- 같은 대상이 여러 번 dirty로 표시되어도 최종 snapshot 기준으로 덮어쓴다.
- batch 중복 실행 또는 재시도에도 결과가 달라지지 않아야 한다.

## Dirty 제거 기준

dirty 대상은 DB 반영 성공 이후에만 제거한다.

권장 처리:

- DB upsert 성공: dirty 대상 제거
- DB upsert 실패: dirty 대상 유지
- Redis snapshot 누락: dirty 대상 유지 후 hydration 또는 cleanup 대상으로 기록
- 삭제된 포트폴리오: 별도 delete 이벤트 또는 tombstone 정책에 따라 처리

## 최신성 기준

batch sync가 이벤트 처리보다 오래된 snapshot으로 Redis 또는 DB를 덮어쓰지 않도록 최신성 기준이 필요하다.

추천 방식:

- 초기 구현은 `updatedAt` 기준을 사용한다.
- 이벤트 처리 시 Redis snapshot에 `updatedAt`을 함께 기록한다.
- batch write-back 시 DB의 `updatedAt`보다 Redis snapshot의 `updatedAt`이 최신일 때만 반영한다.
- 추후 이벤트 순서 보장이 더 중요해지면 event version 또는 snapshot version으로 전환한다.

## 유저 현재 평가액

초기 구현에서는 유저 현재 평가액을 포트폴리오 평가액 합산으로 계산한다.

이 방식의 장점:

- 데이터 중복이 적다.
- 포트폴리오 currentValue만 정확하면 유저 currentValue를 재계산할 수 있다.
- 구현이 단순하다.

주의점:

- 유저가 보유한 포트폴리오 수가 많아지면 계산 비용이 증가한다.
- 병목이 확인되면 유저 currentValue를 delta 방식으로 별도 관리하는 구조로 전환한다.

## Redis 원자성

초기 구현에서는 현재 Java adapter의 순차 Redis 명령을 유지한다.

추후 최적화 우선순위:

1. 주가 변경 시 포트폴리오 currentValue delta 계산을 Lua script로 묶는다.
2. 매수/매도 시 수량 변경, 종목 인덱스 변경, 평가액 변경을 Lua script로 묶는다.
3. valuation snapshot 저장과 dirty 표시를 Lua script로 묶는다.

Lua 적용 전 확인 사항:

- Redis Cluster 사용 여부
- 같은 Lua script에서 접근하는 key들이 같은 hash slot에 배치되는지 여부
- batch sync와 event sync의 동시성 정책

## 운영 체크리스트

- dirty 대상 처리량과 backlog를 모니터링한다.
- DB upsert 실패율을 모니터링한다.
- Redis snapshot 필드 누락을 별도 지표로 기록한다.
- batch 실행 시간이 이벤트 처리 지연을 만들지 않도록 batch size를 제한한다.
- Redis 장애 복구용 hydration job을 별도로 준비한다.
