package depth.finvibe.profit.worker.application.exception;

public class ProfitCacheCorruptedException extends RuntimeException {
    private final Long stockId;
    private final Long portfolioId;
    private final Long userId;
    private final String reason;

    public ProfitCacheCorruptedException(Long stockId, Long portfolioId, Long userId, String reason) {
        super(message(stockId, portfolioId, userId, reason));
        this.stockId = stockId;
        this.portfolioId = portfolioId;
        this.userId = userId;
        this.reason = reason;
    }

    public Long getStockId() {
        return stockId;
    }

    public Long getPortfolioId() {
        return portfolioId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getReason() {
        return reason;
    }

    private static String message(Long stockId, Long portfolioId, Long userId, String reason) {
        return "Profit cache corrupted: stockId=" + stockId
                + ", portfolioId=" + portfolioId
                + ", userId=" + userId
                + ", reason=" + reason;
    }
}
