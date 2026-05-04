package depth.finvibe.profit.worker.application.port.in;

import depth.finvibe.profit.worker.dto.ProfitDto;

public interface ProfitUseCase {
    void updateProfits(ProfitDto.ProfitRecalculateRequest request);
}
