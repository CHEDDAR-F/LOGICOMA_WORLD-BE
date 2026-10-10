package com.ai.repo.playground.rules;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V7StoryRulesTest {
    private final V7StoryRules rules = new V7StoryRules();
    private V6MonthlyPlan plan(V6MonthlyPlan.ProductionBand band, V6MonthlyPlan.MarketingAction marketing,
                               V6MonthlyPlan.ServiceFocus service) {
        return new V6MonthlyPlan(band, marketing, service, null);
    }
    @Test void communityChoiceCreatesTraceableNextMonthOpportunity() {
        var chosen = plan(V6MonthlyPlan.ProductionBand.STANDARD, V6MonthlyPlan.MarketingAction.COMMUNITY_EVENT,
                V6MonthlyPlan.ServiceFocus.RELATIONSHIP);
        var actual = rules.next(5, 3, V6ConflictRules.StoryState.initial(), new V7StoryRules.Execution(2, chosen, true, true, true), false);
        assertEquals(V6ConflictRules.Kind.RUSH_BULK_ORDER, actual.kind());
        assertEquals(new V7StoryRules.Cause(2, "marketingAction", "COMMUNITY_EVENT"), actual.cause());
        assertEquals(actual, rules.next(5, 3, V6ConflictRules.StoryState.initial(), new V7StoryRules.Execution(2, chosen, true, true, true), false));
        assertNull(rules.next(5, 3, V6ConflictRules.StoryState.initial(),
                new V7StoryRules.Execution(2, plan(V6MonthlyPlan.ProductionBand.STANDARD, V6MonthlyPlan.MarketingAction.NONE,
                        V6MonthlyPlan.ServiceFocus.RELATIONSHIP), false, false, true), false).kind());
    }
    @Test void expansionAndSpeedDependOnPersistedSupplyAndTrust() {
        var strained = new V6ConflictRules.StoryState(-1, -1, 0, 1, null);
        var expanded = plan(V6MonthlyPlan.ProductionBand.EXPAND, V6MonthlyPlan.MarketingAction.NONE,
                V6MonthlyPlan.ServiceFocus.FULFILLMENT);
        assertEquals(V6ConflictRules.Kind.SUPPLIER_SHORTAGE, rules.next(2, 4, strained, new V7StoryRules.Execution(3, expanded, true, false, true), false).kind());
        assertNull(rules.next(2, 4, V6ConflictRules.StoryState.initial(), new V7StoryRules.Execution(3, expanded, true, false, true), false).kind());
        var fast = plan(V6MonthlyPlan.ProductionBand.STANDARD, V6MonthlyPlan.MarketingAction.NONE,
                V6MonthlyPlan.ServiceFocus.SPEED);
        assertEquals(V6ConflictRules.Kind.QUALITY_DOUBT, rules.next(2, 4, strained, new V7StoryRules.Execution(3, fast, false, false, true), false).kind());
    }
    @Test void fallbackStaleChoicesAndRepeatedFamiliesNeverInventACause() {
        var chosen = plan(V6MonthlyPlan.ProductionBand.STANDARD, V6MonthlyPlan.MarketingAction.COMMUNITY_EVENT,
                V6MonthlyPlan.ServiceFocus.RELATIONSHIP);
        assertNull(rules.next(1, 4, V6ConflictRules.StoryState.initial(), null, false).cause());
        assertNull(rules.next(1, 4, V6ConflictRules.StoryState.initial(), new V7StoryRules.Execution(2, chosen, true, true, true), false).cause());
        var repeated = new V6ConflictRules.StoryState(0, 0, 0, 1, V6ConflictRules.Kind.RUSH_BULK_ORDER);
        assertNull(rules.next(1, 4, repeated, new V7StoryRules.Execution(3, chosen, true, true, true), false).kind());
        var scheduled = rules.next(1, 4, repeated, new V7StoryRules.Execution(3, chosen, true, true, true), true);
        assertNull(scheduled.cause());
        assertNotEquals(repeated.previous().family(), scheduled.kind().family());
    }
}
