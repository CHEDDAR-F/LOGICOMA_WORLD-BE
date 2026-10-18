package com.ai.repo.playground.rules;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class V6MonthlyPlanTest {
    private final V5ShopRules.Strategy signed=new V5ShopRules.Strategy(4,16,2_000,
            V5ShopRules.Audience.NEIGHBORS,V5ShopRules.Channel.FLYERS,
            V5ShopRules.ServicePromise.COMMUNITY,300);

    @Test void fullPlanChangesExecutableEconomicsWithoutChangingSignedPriceOrReserve() {
        V6MonthlyPlan conservative=new V6MonthlyPlan(V6MonthlyPlan.ProductionBand.CONSERVATIVE,
                V6MonthlyPlan.MarketingAction.NONE,V6MonthlyPlan.ServiceFocus.FULFILLMENT,null);
        V6MonthlyPlan expanded=new V6MonthlyPlan(V6MonthlyPlan.ProductionBand.EXPAND,
                V6MonthlyPlan.MarketingAction.COMMUNITY_EVENT,V6MonthlyPlan.ServiceFocus.RELATIONSHIP,null);
        assertEquals(3,conservative.strategy(signed).produceUnits());
        assertEquals(0,conservative.strategy(signed).monthlyMarketingBudgetMinor());
        assertEquals(5,expanded.strategy(signed).produceUnits());
        assertEquals(600,expanded.strategy(signed).monthlyMarketingBudgetMinor());
        assertEquals(signed.unitPriceCoins(),expanded.strategy(signed).unitPriceCoins());
        assertEquals(signed.minimumReserveMinor(),expanded.strategy(signed).minimumReserveMinor());
    }

    @Test void incidentResponseIsRequiredOnlyForTheDrawnConflict() {
        V6MonthlyPlan absent=new V6MonthlyPlan(V6MonthlyPlan.ProductionBand.STANDARD,
                V6MonthlyPlan.MarketingAction.NONE,V6MonthlyPlan.ServiceFocus.SPEED,null);
        absent.validateFor(null);
        assertEquals("INVALID_V6_CONFLICT_CHOICE",assertThrows(IllegalArgumentException.class,
                ()->absent.validateFor(V6ConflictRules.Kind.SUPPLIER_SHORTAGE)).getMessage());
        V6MonthlyPlan valid=new V6MonthlyPlan(V6MonthlyPlan.ProductionBand.STANDARD,
                V6MonthlyPlan.MarketingAction.NONE,V6MonthlyPlan.ServiceFocus.SPEED,"PAY_ALTERNATE");
        valid.validateFor(V6ConflictRules.Kind.SUPPLIER_SHORTAGE);
        assertEquals("INCIDENT_RESPONSE_WITHOUT_CONFLICT",assertThrows(IllegalArgumentException.class,
                ()->valid.validateFor(null)).getMessage());
        assertEquals("INVALID_V6_CONFLICT_CHOICE",assertThrows(IllegalArgumentException.class,
                ()->valid.validateFor(V6ConflictRules.Kind.RENT_INCREASE)).getMessage());
    }
}
