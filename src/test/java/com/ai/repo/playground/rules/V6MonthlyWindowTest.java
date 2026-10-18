package com.ai.repo.playground.rules;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V6MonthlyWindowTest {
    private final Instant now=Instant.parse("2026-10-03T10:00:00Z");
    private V6MonthlyWindow start() {
        return V6MonthlyWindow.open("room:12","plan-2",5,92,93,now.plusSeconds(900),
                V6ConflictRules.Kind.SUPPLIER_SHORTAGE);
    }
    @Test void matchingIndependentPositionsAreConsensusWithoutForcedDebate() {
        V6MonthlyWindow first=start().position(92,"plan-2","WAIT_RESTOCK",now);
        assertEquals(V6MonthlyWindow.Phase.AWAIT_SECOND,first.phase());
        assertThrows(IllegalArgumentException.class,()->first.position(92,"plan-2","WAIT_RESTOCK",now));
        V6MonthlyWindow agreed=first.position(93,"plan-2","WAIT_RESTOCK",now);
        assertEquals(V6MonthlyWindow.Resolution.INDEPENDENT_CONSENSUS,agreed.resolution());
        assertEquals("WAIT_RESTOCK",agreed.effectiveChoice());
    }
    @Test void disagreementRequiresExplicitConcessionAndPartnerAcceptance() {
        V6MonthlyWindow disputed=start().position(92,"plan-2","WAIT_RESTOCK",now)
                .position(93,"plan-2","PAY_ALTERNATE",now);
        assertEquals(V6MonthlyWindow.Phase.AWAIT_COUNTER,disputed.phase());
        assertThrows(IllegalArgumentException.class,()->disputed.counter(92,"plan-2",
                "VERIFIED_REFURBISHED","WAIT_RESTOCK","PAY_ALTERNATE",now));
        V6MonthlyWindow counter=disputed.counter(92,"plan-2","VERIFIED_REFURBISHED",
                "PAY_ALTERNATE","WAIT_RESTOCK",now);
        assertEquals(V6MonthlyWindow.Phase.AWAIT_FINAL,counter.phase());
        assertThrows(IllegalArgumentException.class,()->counter.accept(92,"plan-2",now));
        V6MonthlyWindow accepted=counter.accept(93,"plan-2",now);
        assertEquals(V6MonthlyWindow.Resolution.COUNTER_ACCEPTED,accepted.resolution());
        assertEquals("VERIFIED_REFURBISHED",accepted.effectiveChoice());
    }
    @Test void refusalAndBudgetFallbackKeepPositionsButNeverInventApproval() {
        V6MonthlyWindow disputed=start().position(92,"plan-2","WAIT_RESTOCK",now)
                .position(93,"plan-2","PAY_ALTERNATE",now);
        V6MonthlyWindow declined=disputed.decline(92,"plan-2",now);
        assertEquals(V6MonthlyWindow.Resolution.DECLINED,declined.resolution());
        assertNull(declined.effectiveChoice());
        V6MonthlyWindow missed=disputed.miss(V6MonthlyWindow.Resolution.BUDGET_FALLBACK);
        assertEquals("WAIT_RESTOCK",missed.firstPosition());
        assertEquals("PAY_ALTERNATE",missed.secondPosition());
        assertNull(missed.effectiveChoice());
        assertThrows(IllegalArgumentException.class,()->disputed.expire(now));
        assertEquals(V6MonthlyWindow.Resolution.DEADLINE_FALLBACK,
                disputed.expire(now.plusSeconds(901)).resolution());
    }
    @Test void partnerCanExplainASecondCounterButTheExchangeStopsAfterTwoOffers() {
        V6MonthlyWindow disputed=start().position(92,"plan-2","WAIT_RESTOCK",now)
                .position(93,"plan-2","PAY_ALTERNATE",now);
        V6MonthlyWindow first=disputed.counter(92,"plan-2","VERIFIED_REFURBISHED",
                "PAY_ALTERNATE","WAIT_RESTOCK",now);
        assertEquals(1,first.counterRounds());
        assertThrows(IllegalArgumentException.class,()->first.counter(93,"plan-2","WAIT_RESTOCK",
                "PAY_ALTERNATE","PAY_ALTERNATE",now));
        V6MonthlyWindow reply=first.counter(93,"plan-2","WAIT_RESTOCK",
                "VERIFIED_REFURBISHED","PAY_ALTERNATE",now);
        assertEquals(V6MonthlyWindow.Phase.AWAIT_LAST_REPLY,reply.phase());
        assertEquals(2,reply.counterRounds());
        assertEquals("WAIT_RESTOCK",reply.latestOffer());
        assertThrows(IllegalArgumentException.class,()->reply.counter(92,"plan-2","PAY_ALTERNATE",
                "WAIT_RESTOCK","WAIT_RESTOCK",now));
        assertThrows(IllegalArgumentException.class,()->reply.accept(93,"plan-2",now));
        V6MonthlyWindow accepted=reply.accept(92,"plan-2",now);
        assertEquals("WAIT_RESTOCK",accepted.effectiveChoice());
        assertEquals(V6MonthlyWindow.Resolution.COUNTER_ACCEPTED,accepted.resolution());
        assertNull(reply.decline(92,"plan-2",now).effectiveChoice());
        assertEquals(V6MonthlyWindow.Resolution.BUDGET_FALLBACK,
                reply.miss(V6MonthlyWindow.Resolution.BUDGET_FALLBACK).resolution());
    }
    @Test void agentCannotDeclineItsOwnUnchangedPosition() {
        V6MonthlyWindow disputed=start().position(92,"plan-2","WAIT_RESTOCK",now)
                .position(93,"plan-2","PAY_ALTERNATE",now);
        V6MonthlyWindow offer=disputed.counter(92,"plan-2","PAY_ALTERNATE",
                "PAY_ALTERNATE","WAIT_RESTOCK",now);
        assertEquals("V6_DECLINE_OWN_POSITION",assertThrows(IllegalArgumentException.class,
                ()->offer.decline(93,"plan-2",now)).getMessage());
        assertEquals("PAY_ALTERNATE",offer.accept(93,"plan-2",now).effectiveChoice());
    }
}
