package com.ai.repo.playground.rules;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class V9CapacityOrderTest {
    final MonthlyShopRules ledger=new MonthlyShopRules();
    MonthlyShopRules.State state(MonthlyShopRules.Shock shock) {
        return new MonthlyShopRules.State(12,3,null,20000,List.of(new MonthlyShopRules.Batch(20,600)),
                List.of(),new MonthlyShopRules.Environment(shock,4,6),MonthlyShopRules.Ending.RUNNING);
    }
    MonthlyShopRules.CapacityTrade trade(MonthlyShopRules.State state,V9CapacityOrder.Contract c,int produce) {
        return ledger.advanceCapacityMonth(state,new MonthlyShopRules.Plan(produce,12,0),
                MonthlyShopRules.TradingAdjustment.NONE,V9CapacityOrder.reservation(c,state.operatedMonths()+1));
    }
    @Test void largeOrderReservesFourSlotsEvenWithSavedInventory() {
        var result=trade(state(MonthlyShopRules.Shock.NONE),V9CapacityOrder.sign(V9CapacityOrder.Choice.LARGE),0);
        assertEquals(4,result.delivery().deliveredUnits()); assertEquals(2,result.delivery().regularCapacity());
        assertEquals(2,result.delivery().regularSoldUnits()); assertEquals(6,result.game().reports().get(0).soldUnits());
        assertEquals(6800,result.delivery().revenueMinor());
        var report=result.game().reports().get(0);
        assertEquals(report.openingCashMinor()+report.receiptsMinor()-report.paymentsMinor(),report.closingCashMinor());
        assertEquals(report.salesMinor()-report.costOfGoodsSoldMinor()-report.operatingExpenseMinor(),report.profitMinor());
    }
    @Test void threeMonthChoiceChangesEveryMonthAndReleasesSlotsAfterCompletion() {
        long[] finalCash=new long[3];int i=0;
        for(var choice:V9CapacityOrder.Choice.values()) {
            var c=choice==V9CapacityOrder.Choice.DECLINE?null:V9CapacityOrder.sign(choice);
            var s=state(MonthlyShopRules.Shock.NONE);
            for(int m=4;m<=6;m++) {
                var t=trade(s,c,6);s=t.game();
                if(c!=null) { c=V9CapacityOrder.record(c,t.delivery());
                    assertEquals(m==6?(choice==V9CapacityOrder.Choice.LARGE?1500:800):0,t.delivery().bonusMinor()); }
            }
            if(c!=null) { assertEquals(V9CapacityOrder.Status.COMPLETED,c.status());assertEquals(3,c.deliveries().size()); }
            assertNull(V9CapacityOrder.reservation(c,7));finalCash[i++]=s.cashMinor();
        }
        assertNotEquals(finalCash[0],finalCash[1]);assertNotEquals(finalCash[1],finalCash[2]);
    }
    @Test void outageMissDoesNotEraseFutureObligationsOrPayBonus() {
        var c=V9CapacityOrder.sign(V9CapacityOrder.Choice.LARGE);var s=state(MonthlyShopRules.Shock.POWER_OUTAGE);
        for(int m=4;m<=6;m++) {
            var t=trade(s,c,6);s=t.game();assertEquals(2,t.delivery().deliveredUnits());
            assertEquals(0,t.delivery().regularCapacity());assertEquals(1400,t.delivery().penaltyMinor());
            assertEquals(0,t.delivery().bonusMinor());
            var trust=V9CapacityOrder.afterDelivery(V6ConflictRules.StoryState.initial(),t.delivery());
            assertEquals(-1,trust.trust());assertEquals(2,trust.echoMonths());c=V9CapacityOrder.record(c,t.delivery());
            assertEquals(m==6?V9CapacityOrder.Status.BREACHED:V9CapacityOrder.Status.ACTIVE,c.status());
        }
    }
    @Test void penaltyCannotCreateDebtAndClosingCannotFabricateDelivery() {
        var s=new MonthlyShopRules.State(12,3,null,2100,List.of(),List.of(),
                new MonthlyShopRules.Environment(MonthlyShopRules.Shock.NONE,4,6),MonthlyShopRules.Ending.RUNNING);
        var c=V9CapacityOrder.sign(V9CapacityOrder.Choice.LARGE);var t=trade(s,c,0);
        assertEquals(100,t.delivery().penaltyMinor());assertEquals(0,t.game().cashMinor());
        c=V9CapacityOrder.record(c,t.delivery());var failed=trade(t.game(),c,0);
        assertNull(failed.delivery());assertEquals(4,failed.game().operatedMonths());
        assertEquals(V9CapacityOrder.Status.CANCELLED,V9CapacityOrder.cancel(c).status());
    }
    @Test void positionsRequireExactOfferAndExplicitConfirmation() {
        var now=Instant.parse("2026-10-18T00:00:00Z");
        var w=V9OrderWindow.open("room:1",3,92,93,now.plusSeconds(60));
        w=w.position(92,w.currentOfferVersion(),V9CapacityOrder.Choice.LARGE,now);
        var first=w;assertThrows(IllegalArgumentException.class,()->first.position(93,"room:1:0",V9CapacityOrder.Choice.SMALL,now));
        w=w.position(93,w.currentOfferVersion(),V9CapacityOrder.Choice.SMALL,now);
        assertNull(w.effectivePlan());
        w=w.reply(92,w.currentOfferVersion(),V9CapacityOrder.Choice.SMALL,true,now);
        w=w.accept(93,w.currentOfferVersion(),now);assertEquals(V9CapacityOrder.Choice.SMALL,w.effectivePlan());
        var missed=V9OrderWindow.open("room:2",3,92,93,now.plusSeconds(60)).miss(V9OrderWindow.Resolution.MODEL_FAILURE_FALLBACK);
        assertNull(missed.effectivePlan());
    }
}
