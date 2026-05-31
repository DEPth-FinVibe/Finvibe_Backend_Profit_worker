package depth.finvibe.profit.worker.application.port.in;

import depth.finvibe.profit.worker.dto.ProfitCalculationDto;

import java.util.List;

public interface ProfitCalculationUseCase {
    void updateProfitByStockPriceChange(ProfitCalculationDto.ProfitCalculationRequest request);

    void updateProfitsByStockPriceChanges(List<ProfitCalculationDto.ProfitCalculationRequest> requests);
}
