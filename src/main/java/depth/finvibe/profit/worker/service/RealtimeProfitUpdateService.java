package depth.finvibe.profit.worker.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import depth.finvibe.profit.worker.consumer.dto.StockPriceUpdatedEvent;
import depth.finvibe.profit.worker.domain.ProfitCalculation;
import depth.finvibe.profit.worker.redis.PortfolioAssetSnapshotRedisRepository;
import depth.finvibe.profit.worker.redis.PortfolioAssetSnapshotRedisRepository.AssetSnapshot;
import depth.finvibe.profit.worker.redis.PortfolioOwnerRedisRepository;
import depth.finvibe.profit.worker.redis.StockHoldingIndexRedisRepository;
import depth.finvibe.profit.worker.redis.UserProfitRankingRedisRepository;
import depth.finvibe.profit.worker.redis.UserProfitSummaryRedisRepository;

/**
 * 실시간 수익률 갱신 파이프라인 핵심 서비스.
 *
 * 흐름:
 * 1. Kafka Consumer가 가격 이벤트를 PriceCoalescingBuffer에 적재
 * 2. 10초 주기 스케줄러가 버퍼를 drain
 * 3. 종목별로 영향받는 포트폴리오 조회 → 수익률 계산 → 유저별 집계
 * 4. Redis ZSET(랭킹) + HASH(수익률 요약) 갱신
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeProfitUpdateService {

	private static final String DAILY_RANK_TYPE = "daily";

	private final PriceCoalescingBuffer priceCoalescingBuffer;
	private final StockHoldingIndexRedisRepository stockHoldingIndexRedisRepository;
	private final PortfolioAssetSnapshotRedisRepository portfolioAssetSnapshotRedisRepository;
	private final PortfolioOwnerRedisRepository portfolioOwnerRedisRepository;
	private final UserProfitRankingRedisRepository userProfitRankingRedisRepository;
	private final UserProfitSummaryRedisRepository userProfitSummaryRedisRepository;
	private final CurrentPriceCache currentPriceCache;
	private final MeterRegistry meterRegistry;
	@Value("${worker.realtime-profit-update.enabled:true}")
	private boolean realtimeProfitUpdateEnabled;

	@Scheduled(fixedRateString = "${worker.coalescing.window-seconds:10}000")
	public void flush() {
		Map<Long, StockPriceUpdatedEvent> events = priceCoalescingBuffer.drainAll();
		if (events.isEmpty()) {
			return;
		}

		if (!realtimeProfitUpdateEnabled) {
			log.info("Realtime profit update disabled. Drained {} coalesced stock events without processing", events.size());
			return;
		}

		Timer.Sample sample = Timer.start(meterRegistry);

		// 현재가 캐시 갱신
		for (StockPriceUpdatedEvent event : events.values()) {
			currentPriceCache.put(event.getStockId(), event.getPrice());
		}

		// 영향받는 포트폴리오 수집
		Map<Long, Set<Long>> stockToPortfolios = new HashMap<>();
		for (Long stockId : events.keySet()) {
			Set<Long> portfolioIds = stockHoldingIndexRedisRepository.getPortfolioIds(stockId);
			if (!portfolioIds.isEmpty()) {
				stockToPortfolios.put(stockId, portfolioIds);
			}
		}

		// 영향받는 포트폴리오 → 유저별 수익률 집계
		Map<UUID, UserProfitAccumulator> userProfits = new HashMap<>();

		// 모든 영향받는 portfolioId 수집 (중복 제거)
		Set<Long> allAffectedPortfolioIds = stockToPortfolios.values().stream()
				.flatMap(Set::stream)
				.collect(java.util.stream.Collectors.toSet());

		for (Long portfolioId : allAffectedPortfolioIds) {
			processPortfolio(portfolioId, userProfits);
		}

		// Redis 갱신
		int updatedUsers = 0;
		for (Map.Entry<UUID, UserProfitAccumulator> entry : userProfits.entrySet()) {
			UUID userId = entry.getKey();
			UserProfitAccumulator acc = entry.getValue();

			ProfitCalculation.PortfolioResult result = ProfitCalculation.aggregatePortfolio(
					acc.totalCurrentValue, acc.totalProfitLoss, acc.totalPurchaseAmount
			);

			userProfitSummaryRedisRepository.update(
					userId,
					result.getTotalCurrentValue(),
					result.getTotalProfitLoss(),
					result.getReturnRate()
			);

			userProfitRankingRedisRepository.updateScore(
					DAILY_RANK_TYPE,
					userId,
					result.getReturnRate().doubleValue()
			);

			updatedUsers++;
		}

		sample.stop(meterRegistry.timer("profit.update.flush"));
		meterRegistry.counter("profit.update.stocks").increment(events.size());
		meterRegistry.counter("profit.update.portfolios").increment(allAffectedPortfolioIds.size());
		meterRegistry.counter("profit.update.users").increment(updatedUsers);

		log.info("Profit update flush: {} stocks, {} portfolios, {} users updated",
				events.size(), allAffectedPortfolioIds.size(), updatedUsers);
	}

	private void processPortfolio(Long portfolioId, Map<UUID, UserProfitAccumulator> userProfits) {
		UUID userId = portfolioOwnerRedisRepository.get(portfolioId);
		if (userId == null) {
			log.debug("No owner found for portfolioId={}", portfolioId);
			return;
		}

		Map<Long, AssetSnapshot> assets = portfolioAssetSnapshotRedisRepository.getAssets(portfolioId);
		if (assets.isEmpty()) {
			return;
		}

		BigDecimal portfolioCurrentValue = BigDecimal.ZERO;
		BigDecimal portfolioProfitLoss = BigDecimal.ZERO;
		BigDecimal portfolioPurchaseAmount = BigDecimal.ZERO;

		for (Map.Entry<Long, AssetSnapshot> assetEntry : assets.entrySet()) {
			Long stockId = assetEntry.getKey();
			AssetSnapshot snapshot = assetEntry.getValue();

			BigDecimal currentPrice = currentPriceCache.get(stockId);
			if (currentPrice == null) {
				// 현재가가 없는 종목은 매입가 기준으로 계산 (변동 없음)
				portfolioCurrentValue = portfolioCurrentValue.add(snapshot.purchasePriceAmount());
				portfolioPurchaseAmount = portfolioPurchaseAmount.add(snapshot.purchasePriceAmount());
				continue;
			}

			ProfitCalculation.AssetResult assetResult = ProfitCalculation.calculateAsset(
					snapshot.amount(), snapshot.purchasePriceAmount(), currentPrice
			);

			portfolioCurrentValue = portfolioCurrentValue.add(assetResult.getCurrentValue());
			portfolioProfitLoss = portfolioProfitLoss.add(assetResult.getProfitLoss());
			portfolioPurchaseAmount = portfolioPurchaseAmount.add(snapshot.purchasePriceAmount());
		}

		UserProfitAccumulator acc = userProfits.computeIfAbsent(userId, k -> new UserProfitAccumulator());
		acc.totalCurrentValue = acc.totalCurrentValue.add(portfolioCurrentValue);
		acc.totalProfitLoss = acc.totalProfitLoss.add(portfolioProfitLoss);
		acc.totalPurchaseAmount = acc.totalPurchaseAmount.add(portfolioPurchaseAmount);
	}

	private static class UserProfitAccumulator {
		BigDecimal totalCurrentValue = BigDecimal.ZERO;
		BigDecimal totalProfitLoss = BigDecimal.ZERO;
		BigDecimal totalPurchaseAmount = BigDecimal.ZERO;
	}
}
