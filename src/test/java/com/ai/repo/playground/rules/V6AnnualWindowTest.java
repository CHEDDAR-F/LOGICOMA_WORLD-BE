package com.ai.repo.playground.rules;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class V6AnnualWindowTest {
    private final Instant now=Instant.parse("2026-10-05T10:00:00Z");
    private V6MonthlyPlan plan(V6MonthlyPlan.ProductionBand band) {
        return new V6MonthlyPlan(band,V6MonthlyPlan.MarketingAction.LOCAL_PROMOTION,
                V6MonthlyPlan.ServiceFocus.RELATIONSHIP,null);
    }
    private V6AnnualWindow open() {
        return V6AnnualWindow.open("room:22",2,92,93,now.plusSeconds(900),null);
    }
    private V6AnnualWindow differing() {
        V6AnnualWindow first=open().position(92,"room:22:0",plan(V6MonthlyPlan.ProductionBand.STANDARD),now);
        return first.position(93,first.currentOfferVersion(),plan(V6MonthlyPlan.ProductionBand.EXPAND),now);
    }

    @Test void matchingIndependentPlansStillRequireAnExplicitConfirmation() {
        V6AnnualWindow first=open().position(92,"room:22:0",plan(V6MonthlyPlan.ProductionBand.STANDARD),now);
        V6AnnualWindow second=first.position(93,first.currentOfferVersion(),first.firstPlan(),now);
        assertEquals(V6AnnualWindow.Phase.AWAIT_CONFIRM,second.phase());
        assertNull(second.effectivePlan());
        assertEquals(92,second.currentActor());
        assertEquals(V6AnnualWindow.Resolution.CONFIRMED_MATCH,
                second.accept(92,second.currentOfferVersion(),now).resolution());
    }

    @Test void debateUsesFullPlanDeltaAndExactOfferRevision() {
        V6AnnualWindow difference=differing();
        assertEquals("STALE_V6_ANNUAL_OFFER",assertThrows(IllegalArgumentException.class,
                ()->difference.reply(92,"room:22:1",difference.firstPlan(),false,now)).getMessage());
        assertEquals("V6_REPLY_PLAN_MISMATCH",assertThrows(IllegalArgumentException.class,
                ()->difference.reply(92,difference.currentOfferVersion(),difference.firstPlan(),true,now)).getMessage());
        V6AnnualWindow defended=difference.reply(92,difference.currentOfferVersion(),
                difference.firstPlan(),false,now);
        assertEquals(V6AnnualWindow.Phase.AWAIT_SECOND_REPLY,defended.phase());
        V6AnnualWindow accepted=defended.accept(93,defended.currentOfferVersion(),now);
        assertEquals(difference.firstPlan(),accepted.effectivePlan());
        assertEquals(V6AnnualWindow.Resolution.COUNTER_ACCEPTED,accepted.resolution());
    }

    @Test void cannotDeclineOwnPlanAndCanRetractWithoutFabricatedConsensus() {
        V6AnnualWindow difference=differing();
        V6AnnualWindow joined=difference.reply(92,difference.currentOfferVersion(),
                difference.secondPlan(),true,now);
        assertEquals("V6_DECLINE_OWN_PLAN",assertThrows(IllegalArgumentException.class,
                ()->joined.decline(93,joined.currentOfferVersion(),now)).getMessage());
        V6AnnualWindow retracted=joined.retract(93,joined.currentOfferVersion(),now);
        assertEquals(V6AnnualWindow.Resolution.RETRACTED,retracted.resolution());
        assertNull(retracted.effectivePlan());
        V6AnnualWindow missed=difference.miss(V6AnnualWindow.Resolution.MODEL_FAILURE_FALLBACK);
        assertNull(missed.effectivePlan());
    }
}
