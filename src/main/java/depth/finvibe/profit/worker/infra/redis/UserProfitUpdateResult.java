package depth.finvibe.profit.worker.infra.redis;

public record UserProfitUpdateResult(
        Double oldUserUnrealizedProfit,
        Double newUserUnrealizedProfit,
        Double oldUserReturnRate,
        Double newUserReturnRate
) {
}
