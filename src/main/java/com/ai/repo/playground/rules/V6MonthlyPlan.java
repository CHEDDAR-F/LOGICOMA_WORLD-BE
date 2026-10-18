package com.ai.repo.playground.rules;

/** A complete, finite next-month operating choice. Agents select codes; the server
 * derives production, marketing spend and service effects from the signed shop.
 */
public record V6MonthlyPlan(ProductionBand productionBand, MarketingAction marketingAction,
        ServiceFocus serviceFocus, String incidentResponse) {
    public enum ProductionBand { CONSERVATIVE, STANDARD, EXPAND }
    public enum MarketingAction { NONE, LOCAL_PROMOTION, COMMUNITY_EVENT }
    public enum ServiceFocus { FULFILLMENT, SPEED, RELATIONSHIP }

    public V6MonthlyPlan {
        require(productionBand!=null && marketingAction!=null && serviceFocus!=null,
                "INVALID_V6_MONTHLY_PLAN");
        require(incidentResponse==null || incidentResponse.matches("[A-Z0-9_]{1,40}"),
                "INVALID_V6_MONTHLY_PLAN");
    }

    public void validateFor(V6ConflictRules.Kind conflict) {
        if (conflict==null) require(incidentResponse==null,"INCIDENT_RESPONSE_WITHOUT_CONFLICT");
        else require(incidentResponse!=null && conflict.options().stream()
                .anyMatch(option->option.code().equals(incidentResponse)),
                "INVALID_V6_CONFLICT_CHOICE");
    }

    public V5ShopRules.Strategy strategy(V5ShopRules.Strategy signed) {
        require(signed!=null,"INVALID_V6_MONTHLY_PLAN");
        int units=Math.max(0,Math.min(6,signed.produceUnits()+switch (productionBand) {
            case CONSERVATIVE -> -1;
            case STANDARD -> 0;
            case EXPAND -> 1;
        }));
        V5ShopRules.Channel channel=switch (marketingAction) {
            case NONE -> V5ShopRules.Channel.NONE;
            case LOCAL_PROMOTION -> V5ShopRules.Channel.FLYERS;
            case COMMUNITY_EVENT -> V5ShopRules.Channel.LOCAL_EVENT;
        };
        long budget=switch (marketingAction) {
            case NONE -> 0;
            case LOCAL_PROMOTION -> 300;
            case COMMUNITY_EVENT -> 600;
        };
        V5ShopRules.ServicePromise promise=switch (serviceFocus) {
            case FULFILLMENT -> V5ShopRules.ServicePromise.QUIET;
            case SPEED -> V5ShopRules.ServicePromise.FAST;
            case RELATIONSHIP -> V5ShopRules.ServicePromise.COMMUNITY;
        };
        return new V5ShopRules.Strategy(units,signed.unitPriceCoins(),signed.minimumReserveMinor(),
                signed.audience(),channel,promise,budget);
    }

    private static void require(boolean valid,String error) {
        if (!valid) throw new IllegalArgumentException(error);
    }
}
