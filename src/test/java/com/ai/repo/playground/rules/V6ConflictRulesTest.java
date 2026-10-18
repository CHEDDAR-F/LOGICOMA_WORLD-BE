package com.ai.repo.playground.rules;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V6ConflictRulesTest {
    private final V6ConflictRules rules=new V6ConflictRules();
    private final MonthlyShopRules ledger=new MonthlyShopRules();

    @Test void deckHasSixteenDistinctThreeWayConflictsAndAvoidsAdjacentFamily() {
        assertEquals(16,V6ConflictRules.Kind.values().length);
        Set<V6ConflictRules.Kind> drawn=new HashSet<>();
        for (int seed=0;seed<250;seed++) {
            V6ConflictRules.StoryState story=V6ConflictRules.StoryState.initial();
            for (int month=1;month<=12;month++) {
                V6ConflictRules.Kind kind=rules.draw(seed,month,story);
                drawn.add(kind);
                assertEquals(3,kind.options().size());
                assertEquals(3,kind.options().stream().map(V6ConflictRules.Option::code).distinct().count());
                if (story.previous()!=null) assertNotEquals(story.previous().family(),kind.family());
                story=rules.resolve(kind,kind.options().get(seed%3).code(),story).next();
            }
        }
        assertEquals(16,drawn.size());
    }

    @Test void sameShortageDifferentAgentChoicesChangeThisMonthAndNextMonth() {
        V6ConflictRules.StoryState start=V6ConflictRules.StoryState.initial();
        V6ConflictRules.Resolution wait=rules.resolve(V6ConflictRules.Kind.SUPPLIER_SHORTAGE,"WAIT_RESTOCK",start);
        V6ConflictRules.Resolution pay=rules.resolve(V6ConflictRules.Kind.SUPPLIER_SHORTAGE,"PAY_ALTERNATE",start);
        assertNotEquals(wait.immediate(),pay.immediate());
        MonthlyShopRules.State opening=ledger.initialize(12,
                new MonthlyShopRules.Environment(MonthlyShopRules.Shock.NONE,1,12));
        MonthlyShopRules.Plan signed=new MonthlyShopRules.Plan(4,16,0);
        MonthlyShopRules.State waitMonth=ledger.advanceMonth(opening,signed,null,wait.immediate());
        MonthlyShopRules.State payMonth=ledger.advanceMonth(opening,signed,null,pay.immediate());
        assertNotEquals(waitMonth.cashMinor(),payMonth.cashMinor());
        V6ConflictRules.Echo waitEcho=rules.advanceEcho(wait.next());
        V6ConflictRules.Echo payEcho=rules.advanceEcho(pay.next());
        assertNotEquals(waitEcho.adjustment(),payEcho.adjustment());
        assertEquals(1,waitEcho.next().echoMonths());
        assertEquals(0,rules.advanceEcho(rules.advanceEcho(waitEcho.next()).next()).next().echoMonths());
        MonthlyShopRules.State waitFollowing=ledger.advanceMonth(waitMonth,signed,null,waitEcho.adjustment());
        MonthlyShopRules.State payFollowing=ledger.advanceMonth(payMonth,signed,null,payEcho.adjustment());
        assertNotEquals(waitFollowing.cashMinor(),payFollowing.cashMinor());
    }

    @Test void invalidChoiceAndForgedAmountsCannotEnterRuleEngine() {
        assertThrows(IllegalArgumentException.class,()->rules.resolve(
                V6ConflictRules.Kind.RENT_INCREASE,"FREE_RENT",V6ConflictRules.StoryState.initial()));
        assertThrows(IllegalArgumentException.class,()->new V6ConflictRules.StoryState(3,0,0,0,null));
        assertThrows(IllegalArgumentException.class,()->rules.draw(-1,5,V6ConflictRules.StoryState.initial()));
    }

    @Test void everyCardOptionProducesABoundedLedgerMonth() {
        MonthlyShopRules.Plan signed=new MonthlyShopRules.Plan(4,16,0);
        for (V6ConflictRules.Kind kind:V6ConflictRules.Kind.values())
            for (V6ConflictRules.Option option:kind.options()) {
                V6ConflictRules.Resolution decision=rules.resolve(kind,option.code(),
                        V6ConflictRules.StoryState.initial());
                MonthlyShopRules.State opening=ledger.initialize(12,
                        new MonthlyShopRules.Environment(MonthlyShopRules.Shock.NONE,1,12));
                MonthlyShopRules.State month=ledger.advanceMonth(opening,signed,null,decision.immediate());
                assertEquals(1,month.reports().size(),kind+" / "+option.code());
                assertTrue(month.cashMinor()>=0,kind+" / "+option.code());
                assertTrue(month.reports().get(0).events().contains(kind.name()));
            }
    }
}
