package com.ai.repo.playground.rules;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class V8NegotiationStakesTest {
    private final V8NegotiationStakes rules = new V8NegotiationStakes();
    private final V5ShopRules.Strategy signed = new V5ShopRules.Strategy(4,20,0,
            V5ShopRules.Audience.NEIGHBORS,V5ShopRules.Channel.NONE,V5ShopRules.ServicePromise.QUIET,0);
    private final V6ConflictRules.StoryState story = V6ConflictRules.StoryState.initial();
    private MonthlyShopRules.State before() {
        var ledger=new MonthlyShopRules();
        return ledger.advanceMonth(ledger.initialize(12,new MonthlyShopRules.Environment(MonthlyShopRules.Shock.NONE,1,12)),
                new MonthlyShopRules.Plan(4,20,0));
    }
    private V6MonthlyPlan plan(String option) {
        return new V6MonthlyPlan(V6MonthlyPlan.ProductionBand.STANDARD,V6MonthlyPlan.MarketingAction.NONE,
                V6MonthlyPlan.ServiceFocus.FULFILLMENT,option);
    }
    private V8NegotiationStakes.Outcome run(MonthlyShopRules.State before,V6ConflictRules.Kind conflict,V6MonthlyPlan plan) {
        return rules.advance(before,signed,V5ShopRules.Signal.NORMAL,null,story,conflict,plan);
    }
    @Test void unresolvedRentStillCostsEightCoins() {
        var state=before();
        var calm=run(state,null,null);
        var missed=run(state,V6ConflictRules.Kind.RENT_INCREASE,null);
        assertEquals(800,calm.result().game().cashMinor()-missed.result().game().cashMinor());
        assertTrue(missed.result().game().reports().get(1).events().contains("UNRESOLVED_INCIDENT"));
        assertEquals(8,missed.story().rentSurchargeCoins());
        assertEquals(state,before());
    }
    @Test void unresolvedShortageHurtsTradeInsteadOfErasingTheHazard() {
        boolean changed=false;
        for(int seed=0;seed<20;seed++) {
            var old=before();
            var state=new MonthlyShopRules.State(12,1,null,old.cashMinor(),old.inventory(),old.reports(),
                    new MonthlyShopRules.Environment(MonthlyShopRules.Shock.NONE,1,12,seed),MonthlyShopRules.Ending.RUNNING);
            changed |= run(state,V6ConflictRules.Kind.SUPPLIER_SHORTAGE,null).result().game().cashMinor()
                    <run(state,null,null).result().game().cashMinor();
            assertEquals(-1,run(state,V6ConflictRules.Kind.SUPPLIER_SHORTAGE,null).story().supply());
        }
        assertTrue(changed);
    }
    @Test void missedOpportunityNeverSignsExclusiveContractOrTakesAnOrder() {
        for (var kind:List.of(V6ConflictRules.Kind.EXCLUSIVE_SUPPLIER,V6ConflictRules.Kind.RUSH_BULK_ORDER,
                V6ConflictRules.Kind.COMMUNITY_EVENT,V6ConflictRules.Kind.UNEXPECTED_DEMAND)) {
            var effect=rules.unresolved(kind);
            assertEquals(0,effect.expenseCoins()); assertEquals(0,effect.addedBuyers());
            assertEquals(0,effect.rentDeltaCoins());
            assertEquals(0,run(before(),kind,null).result().marketingSpentMinor());
        }
    }
    @Test void optionPositionCannotSecretlyDiscountTheSignedPriceOrAddPivotFees() {
        var result=run(before(),V6ConflictRules.Kind.EQUIPMENT_FAILURE,plan("RENT_EQUIPMENT")).result();
        var report=result.game().reports().get(1);
        assertTrue(report.soldUnits()>0);
        assertEquals(report.soldUnits()*2000L,report.salesMinor());
        assertEquals(3600,report.operatingExpenseMinor()); // fixed 20 + published rental 16
        assertEquals(0,result.marketingSpentMinor());
    }
    @Test void counterfactualUsesSameMonthSeedAndCannotChangeActualState() {
        var state=before();
        var first=plan("WAIT_RESTOCK"); var second=plan("PAY_ALTERNATE");
        var actual=run(state,V6ConflictRules.Kind.SUPPLIER_SHORTAGE,second).result();
        var impact=rules.impact(state,signed,V5ShopRules.Signal.NORMAL,null,story,
                V6ConflictRules.Kind.SUPPLIER_SHORTAGE,first,second,actual);
        assertEquals(impact.actual(),impact.secondPosition());
        assertNotEquals(impact.firstPosition(),impact.secondPosition());
        assertEquals("SAME_MONTH_SAME_MARKET",impact.basis());
        assertEquals(actual,run(state,V6ConflictRules.Kind.SUPPLIER_SHORTAGE,second).result());
        assertEquals(state,before());
    }
    @Test void equalEconomicChoicesMustNotBePresentedAsAWin() {
        var state=before();
        var fulfillment=plan(null);
        var relationship=new V6MonthlyPlan(V6MonthlyPlan.ProductionBand.STANDARD,
                V6MonthlyPlan.MarketingAction.NONE,V6MonthlyPlan.ServiceFocus.RELATIONSHIP,null);
        assertEquals(rules.accounts(state,run(state,null,fulfillment).result()),
                rules.accounts(state,run(state,null,relationship).result()));
        var max=new V5ShopRules.Strategy(6,20,0,V5ShopRules.Audience.NEIGHBORS,V5ShopRules.Channel.NONE,V5ShopRules.ServicePromise.QUIET,0);
        assertEquals(6,new V6MonthlyPlan(V6MonthlyPlan.ProductionBand.EXPAND,
                V6MonthlyPlan.MarketingAction.NONE,V6MonthlyPlan.ServiceFocus.FULFILLMENT,null).strategy(max).produceUnits());
    }
    @Test void failedOpeningDoesNotReuseLastMonthsSalesOrProfit() {
        var old=before();
        var poor=new MonthlyShopRules.State(12,1,null,2000,List.of(),old.reports(),old.environment(),MonthlyShopRules.Ending.RUNNING);
        var outcome=run(poor,V6ConflictRules.Kind.RENT_INCREASE,null);
        var accounts=rules.accounts(poor,outcome.result());
        assertFalse(accounts.opened()); assertEquals(0,accounts.soldUnits());
        assertEquals(0,accounts.profitMinor());
        assertEquals(MonthlyShopRules.Ending.BUSINESS_FAILURE,accounts.ending());
    }
    @Test void newCrisisDoesNotErasePersistedRentFromEarlierChoices() {
        var before=before();
        var ongoing=new V6ConflictRules.StoryState(0,0,8,1,V6ConflictRules.Kind.RENT_INCREASE);
        var calm=rules.advance(before,signed,V5ShopRules.Signal.NORMAL,null,story,V6ConflictRules.Kind.EQUIPMENT_FAILURE,null);
        var carried=rules.advance(before,signed,V5ShopRules.Signal.NORMAL,null,ongoing,V6ConflictRules.Kind.EQUIPMENT_FAILURE,null);
        assertEquals(800,calm.result().game().cashMinor()-carried.result().game().cashMinor());
    }
    @Test void everyIncidentHasFiniteDeterministicFallbackAndRejectsInventedChoice() {
        for(var kind:V6ConflictRules.Kind.values()) {
            assertEquals(run(before(),kind,null),run(before(),kind,null));
            assertThrows(IllegalArgumentException.class,()->run(before(),kind,plan("MADE_UP")));
        }
    }
}
