package depth.finvibe.profit.worker.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import lombok.Builder;
import lombok.Getter;

/**
 * 순수 수익률 계산 로직.
 * Monolith의 AssetValuation / PortfolioValuation 계산 로직을 재현.
 */
public final class ProfitCalculation {

	private ProfitCalculation() {
	}

	/**
	 * 개별 자산의 수익률 계산.
	 * AssetValuation.calculate(amount, totalPrice, currentPrice)와 동일.
	 */
	public static AssetResult calculateAsset(BigDecimal amount, BigDecimal purchasePriceAmount, BigDecimal currentPrice) {
		BigDecimal currentValue = amount.multiply(currentPrice);
		BigDecimal profitLoss = currentValue.subtract(purchasePriceAmount);
		BigDecimal returnRate = calculateReturnRate(profitLoss, purchasePriceAmount);

		return AssetResult.builder()
				.currentValue(currentValue)
				.profitLoss(profitLoss)
				.returnRate(returnRate)
				.build();
	}

	/**
	 * 포트폴리오 수준 집계.
	 * PortfolioValuation.aggregate(assetValuations, purchaseAmount)와 동일.
	 */
	public static PortfolioResult aggregatePortfolio(BigDecimal totalCurrentValue, BigDecimal totalProfitLoss, BigDecimal totalPurchaseAmount) {
		BigDecimal returnRate = calculateReturnRate(totalProfitLoss, totalPurchaseAmount);

		return PortfolioResult.builder()
				.totalCurrentValue(totalCurrentValue)
				.totalProfitLoss(totalProfitLoss)
				.returnRate(returnRate)
				.build();
	}

	private static BigDecimal calculateReturnRate(BigDecimal profitLoss, BigDecimal purchaseAmount) {
		if (purchaseAmount.compareTo(BigDecimal.ZERO) == 0) {
			return BigDecimal.ZERO;
		}
		return profitLoss
				.divide(purchaseAmount, 4, RoundingMode.HALF_UP)
				.multiply(BigDecimal.valueOf(100));
	}

	@Getter
	@Builder
	public static class AssetResult {
		private final BigDecimal currentValue;
		private final BigDecimal profitLoss;
		private final BigDecimal returnRate;
	}

	@Getter
	@Builder
	public static class PortfolioResult {
		private final BigDecimal totalCurrentValue;
		private final BigDecimal totalProfitLoss;
		private final BigDecimal returnRate;
	}
}
