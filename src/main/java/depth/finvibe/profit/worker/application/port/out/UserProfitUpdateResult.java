package depth.finvibe.profit.worker.application.port.out;

public record UserProfitUpdateResult(
        Double oldUserUnrealizedProfit,
        Double newUserUnrealizedProfit,
        Double oldUserReturnRate,
        Double newUserReturnRate
) {
}
