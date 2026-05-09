package depth.finvibe.profit.worker.application;

import depth.finvibe.profit.worker.application.exception.ProfitCacheMissException;
import depth.finvibe.profit.worker.application.exception.ProfitCacheMissReason;
import depth.finvibe.profit.worker.application.port.out.MonolithClient;
import depth.finvibe.profit.worker.application.port.out.ProfitCacheWriter;
import depth.finvibe.profit.worker.dto.PortfolioHoldingData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfitCacheHydrationServiceTest {
    @Mock
    private MonolithClient monolithClient;

    @Mock
    private ProfitCacheWriter profitCacheWriter;

    @Mock
    private ObjectProvider<MonolithClient> monolithClientProvider;

    @Mock
    private ObjectProvider<ProfitCacheWriter> profitCacheWriterProvider;

    private ProfitCacheHydrationService profitCacheHydrationService;

    @BeforeEach
    void setUp() {
        when(monolithClientProvider.getIfAvailable()).thenReturn(monolithClient);
        when(profitCacheWriterProvider.getIfAvailable()).thenReturn(profitCacheWriter);

        profitCacheHydrationService = new ProfitCacheHydrationService(monolithClientProvider, profitCacheWriterProvider);
    }

    @Test
    void hydrateFetchesAndWritesPortfolioHoldingWhenHoldingIsMissing() {
        ProfitCacheMissException exception = new ProfitCacheMissException(
                1L,
                10L,
                null,
                ProfitCacheMissReason.PORTFOLIO_HOLDING_MISSING
        );
        PortfolioHoldingData data = new PortfolioHoldingData(
                10L,
                1L,
                BigDecimal.TEN,
                new BigDecimal("90000"),
                new BigDecimal("900000"),
                new BigDecimal("95000"),
                new BigDecimal("50000"),
                new BigDecimal("0.0555555556")
        );
        when(monolithClient.getPortfolioHolding(10L, 1L)).thenReturn(data);

        profitCacheHydrationService.hydrate(exception);

        verify(monolithClient).getPortfolioHolding(10L, 1L);
        verify(profitCacheWriter).writePortfolioHolding(data);
    }
}
