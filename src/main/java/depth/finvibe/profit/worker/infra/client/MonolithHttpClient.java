package depth.finvibe.profit.worker.infra.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import depth.finvibe.profit.worker.application.port.out.MonolithClient;
import depth.finvibe.profit.worker.dto.PortfolioAggregateData;
import depth.finvibe.profit.worker.dto.PortfolioHoldingData;
import depth.finvibe.profit.worker.dto.PortfolioOwnerData;
import depth.finvibe.profit.worker.dto.StockPortfolioMappingData;
import depth.finvibe.profit.worker.dto.UserAggregateData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class MonolithHttpClient implements MonolithClient {
    private static final String STOCK_PORTFOLIO_MAPPING_PATH = "/internal/profit-cache/stocks/%d/portfolios";
    private static final String PORTFOLIO_OWNER_PATH = "/internal/profit-cache/portfolios/%d/owner";
    private static final String PORTFOLIO_HOLDING_PATH = "/internal/profit-cache/portfolios/%d/stocks/%d/holding";
    private static final String PORTFOLIO_AGGREGATE_PATH = "/internal/profit-cache/portfolios/%d/aggregate";
    private static final String USER_AGGREGATE_PATH = "/internal/profit-cache/users/%s/aggregate";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final Duration requestTimeout;

    public MonolithHttpClient(
            @Value("${worker.monolith.base-url:http://localhost:8080}") String baseUrl,
            @Value("${worker.monolith.connect-timeout-ms:1000}") long connectTimeoutMillis,
            @Value("${worker.monolith.read-timeout-ms:3000}") long readTimeoutMillis
    ) {
        this.objectMapper = new ObjectMapper();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.requestTimeout = Duration.ofMillis(readTimeoutMillis);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
    }

    @Override
    public StockPortfolioMappingData getStockPortfolioMapping(Long stockId) {
        if (stockId == null) {
            throw new IllegalArgumentException("stockId must not be null");
        }
        return get(STOCK_PORTFOLIO_MAPPING_PATH.formatted(stockId), StockPortfolioMappingData.class, "stockId=" + stockId);
    }

    @Override
    public PortfolioOwnerData getPortfolioOwner(Long portfolioId) {
        if (portfolioId == null) {
            throw new IllegalArgumentException("portfolioId must not be null");
        }
        return get(PORTFOLIO_OWNER_PATH.formatted(portfolioId), PortfolioOwnerData.class, "portfolioId=" + portfolioId);
    }

    @Override
    public PortfolioHoldingData getPortfolioHolding(Long portfolioId, Long stockId) {
        if (portfolioId == null) {
            throw new IllegalArgumentException("portfolioId must not be null");
        }
        if (stockId == null) {
            throw new IllegalArgumentException("stockId must not be null");
        }
        return get(
                PORTFOLIO_HOLDING_PATH.formatted(portfolioId, stockId),
                PortfolioHoldingData.class,
                "portfolioId=" + portfolioId + ", stockId=" + stockId
        );
    }

    @Override
    public PortfolioAggregateData getPortfolioAggregate(Long portfolioId) {
        if (portfolioId == null) {
            throw new IllegalArgumentException("portfolioId must not be null");
        }
        return get(PORTFOLIO_AGGREGATE_PATH.formatted(portfolioId), PortfolioAggregateData.class, "portfolioId=" + portfolioId);
    }

    @Override
    public UserAggregateData getUserAggregate(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        return get(USER_AGGREGATE_PATH.formatted(userId), UserAggregateData.class, "userId=" + userId);
    }

    private <T> T get(String path, Class<T> responseType, String context) {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(requestTimeout)
                .GET()
                .build();

        HttpResponse<String> response = send(request, context);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Failed to fetch profit cache data from monolith: " + context
                            + ", statusCode=" + response.statusCode()
            );
        }

        return deserialize(response.body(), responseType, context);
    }

    private HttpResponse<String> send(HttpRequest request, String context) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to fetch profit cache data from monolith: " + context, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching profit cache data from monolith: " + context, exception);
        }
    }

    private <T> T deserialize(String body, Class<T> responseType, String context) {
        try {
            return objectMapper.readValue(body, responseType);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse profit cache data from monolith: " + context, exception);
        }
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("worker.monolith.base-url must not be blank");
        }
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
}
