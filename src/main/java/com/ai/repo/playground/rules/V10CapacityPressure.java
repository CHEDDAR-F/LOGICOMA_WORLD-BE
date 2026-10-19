package com.ai.repo.playground.rules;

import java.util.List;

/** Rule 1.4: a disclosed district rush and one limited-service month. */
public final class V10CapacityPressure {
    public static boolean rush(int month) { return month>=4 && month<=6; }
    public static int capacity(int month,int ordinary) { return month==5?Math.min(4,ordinary):ordinary; }
    public record Evidence(int month,int totalCapacity,int producedUnits,int availableUnits,
            int walkInDemand,int orderUnits,int orderDelivered,int walkInCapacity,int walkInSold,
            int unservedWalkIns,int capacityDemandShortfall,int stockShortfall) {}
    public record Report(Evidence evidence,int trustBefore,int trustAfter,int echoMonthsAfter,
            long penaltyMinor,long bonusMinor,int sameMonthWithoutOrderWalkInSold,
            long sameMonthWithoutOrderProfitMinor,long actualProfitMinor) {}
    public record Forecast(int fromMonth,int throughMonth,int baseWalkInDemand,int normalCapacity,
            int restrictedMonth,int restrictedCapacity,int expandedProduction,
            String comparisonBasis,List<Report> reports) {}
    public static Forecast forecast(List<Report> reports) {
        return new Forecast(4,6,6,6,5,4,6,"SAME_MONTH_SAME_MARKET_WITHOUT_ORDER",List.copyOf(reports));
    }
    public static V5ShopRules.Strategy strategy(V6MonthlyPlan plan,V5ShopRules.Strategy signed) {
        var chosen=plan==null?signed:plan.strategy(signed);
        if(plan==null || plan.productionBand()!=V6MonthlyPlan.ProductionBand.EXPAND) return chosen;
        return new V5ShopRules.Strategy(6,chosen.unitPriceCoins(),chosen.minimumReserveMinor(),
                chosen.audience(),chosen.channel(),chosen.servicePromise(),chosen.monthlyMarketingBudgetMinor());
    }
}
