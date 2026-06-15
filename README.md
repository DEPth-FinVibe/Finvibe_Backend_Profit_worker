# Finvibe Profit Worker

Kafka 가격 이벤트를 소비하여 포트폴리오/유저 수익률을 실시간 재계산하고 Redis에 저장하는 워커 서비스입니다.

<img width="1382" height="1042" alt="제목 없는 다이어그램 drawio" src="https://github.com/user-attachments/assets/12d0a566-7a27-45db-a3dc-fb8d91a34d16" />


---

## 병목 추적 로드맵 — 처리량 2 → 91 events/s (45x)

### Step 1. 숨겨져 있던 정합성 문제 발견

- **문제**: `currentValue` 갱신이 GET → modify → SET 패턴 → 동시 처리 시 lost update
  ```
  Thread A (삼성 +500):    GET cvp → 1000
  Thread B (하이닉스 +300): GET cvp → 1000
  Thread A: SET 1500 / Thread B: SET 1300 ← 삼성 +500 유실 (기대값 1800)
  ```
- **왜 지금까지 안 터졌나**: listener concurrency=1이었고, 내부 fanout도 `CompletableFuture`를 stream에서 생성 직후 join하는 lazy evaluation 버그로 **사실상 단일 스레드**
- **해결**: Redis `HINCRBYFLOAT` 단일 커맨드로 원자적 갱신 → 안전하게 병렬화할 수 있는 기반 마련

### Step 2. 배치 + 병렬 처리 — 2 → 10 events/s

- Kafka batch consumption (`max.poll.records=50`)
- 배치 내 동일 stockId 중복 제거 (최신 가격만 유지)
- flat parallelism (parallelism=4) — 중첩 executor 없이 단일 pool로 deadlock 방지

### Step 3. 스레드 확장 — 10 → 20 events/s

- parallelism 4→32로 올렸지만 **2배밖에 안 나옴**
- **원인**: Redis 서버의 소켓 I/O가 단일 스레드라 동시 커맨드 증가에 따라 RTT 3.2ms → 8.8ms로 악화
- 스레드를 8배 늘려도 Redis 쪽이 포화되면서 실제 개선은 2배에 그침

### Step 4. Redis io-threads 튜닝 — 배치 시간 51초 → 8초

- Redis 7.x `io-threads=4`, `io-threads-do-reads=yes` 적용
- 커맨드 실행은 단일 스레드 유지, 네트워크 I/O만 병렬화
- RTT 8.8ms → 2.3ms, 처리량 ~39 ops/s

### Step 5. 벌크 파이프라이닝 — 구조 자체를 전환 → 91 events/s

- **깨달음**: 스레드와 Redis I/O를 아무리 튜닝해도 "포트폴리오당 14 RT"라는 **구조 자체**가 병목
- 4,000 포트폴리오 × 14 = 56,000회 개별 Redis 커맨드/배치
- **해결**: 포트폴리오별 순차 처리 → **배치 단위 7-phase 파이프라인**으로 전면 재구조화
  - 벌크 프리패치 → 인메모리 델타 연산(Redis 호출 없음) → 벌크 라이트
  - 배치당 RT 56,000 → **58** (965x 감소)

### Step 6. Kafka Consumer Rebalance 안정화

- **문제**: 6 파티션에 27개 컨슈머(3 리스너 × concurrency 3 × Pod 3), 19개(70%) 상시 유휴
- Classic Protocol Eager Rebalance → 롤링 배포 시 전체 멤버 Stop-the-World → 일부 파티션 할당 고착 → throughput 33%로 저하
- **해결**: 리스너별 concurrency 최적화(27→12 멤버) + 그룹 분리 + KIP-848 v2 전환 단계적 적용

### 누적 개선

| 단계 | events/s | 배치당 RT 수 | 핵심 요인 |
|------|----------|-------------|----------|
| 초기 (순차, 단건) | ~2 | N/A | - |
| 배치+병렬 | ~10 | ~56,000 | Kafka 배치 + 스레드 병렬화 |
| parallelism 32 | ~20 | ~56,000 | I/O 바운드 스레드 확장 |
| io-threads 4 | ~39 | ~56,000 | Redis 네트워크 I/O 병렬화 |
| **벌크 파이프라이닝** | **~91** | **~58** | **배치당 RT 965x 감소** |

---

## 실행

```bash
./gradlew bootRun
```

Kafka 토픽 `market.stock-price-updated.v1`을 구독합니다.
