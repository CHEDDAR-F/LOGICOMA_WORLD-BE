package com.ai.repo.playground.rules;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class V10CapacityPressureTest {
 final MonthlyShopRules ledger=new MonthlyShopRules();
 final V8NegotiationStakes stakes=new V8NegotiationStakes();
 final V5ShopRules.Strategy signed=new V5ShopRules.Strategy(3,16,0,V5ShopRules.Audience.NEIGHBORS,V5ShopRules.Channel.NONE,V5ShopRules.ServicePromise.QUIET,0);
 MonthlyShopRules.State state(int completed,MonthlyShopRules.Shock shock,int stock) {
  return new MonthlyShopRules.State(12,completed,null,20000,stock==0?List.of():List.of(new MonthlyShopRules.Batch(stock,600)),List.of(),
    new MonthlyShopRules.Environment(shock,5,5,3),MonthlyShopRules.Ending.RUNNING);
 }
 V6MonthlyPlan plan(V6MonthlyPlan.ProductionBand band) { return new V6MonthlyPlan(band,V6MonthlyPlan.MarketingAction.NONE,V6MonthlyPlan.ServiceFocus.FULFILLMENT,null); }
 V8NegotiationStakes.Outcome run(MonthlyShopRules.State s,V9CapacityOrder.Contract c,V6MonthlyPlan p,V6ConflictRules.StoryState story) {
  return stakes.advance(s,signed,s.environment().shock()==MonthlyShopRules.Shock.POWER_OUTAGE && s.operatedMonths()==4?V5ShopRules.Signal.POWER_OUTAGE:V5ShopRules.Signal.NORMAL,null,story,null,p,V9CapacityOrder.reservation(c,s.operatedMonths()+1),true,true);
 }
 @Test void sixSlotRushCreatesRealWalkInOpportunityCost() {
  var s=state(3,MonthlyShopRules.Shock.NONE,0);var expanded=plan(V6MonthlyPlan.ProductionBand.EXPAND);
  var with=run(s,V9CapacityOrder.sign(V9CapacityOrder.Choice.LARGE),expanded,V6ConflictRules.StoryState.initial());
  var without=run(s,null,expanded,V6ConflictRules.StoryState.initial());
  assertEquals(6,with.pressure().producedUnits());assertEquals(4,with.delivery().deliveredUnits());
  assertEquals(2,with.pressure().walkInSold());assertEquals(6,without.pressure().walkInSold());
  assertTrue(with.pressure().capacityDemandShortfall()>0);assertEquals(0,with.pressure().stockShortfall());
  assertEquals(6,with.result().game().reports().get(0).soldUnits());
 }
 @Test void restrictedMonthSharesFourSlotsAndCannotBeBypassedByInventory() {
  var c=V9CapacityOrder.sign(V9CapacityOrder.Choice.LARGE);
  var first=run(state(3,MonthlyShopRules.Shock.NONE,20),c,plan(V6MonthlyPlan.ProductionBand.EXPAND),V6ConflictRules.StoryState.initial());c=V9CapacityOrder.record(c,first.delivery());
  var next=run(first.result().game(),c,plan(V6MonthlyPlan.ProductionBand.EXPAND),first.story());
  assertEquals(4,next.pressure().totalCapacity());assertEquals(4,next.delivery().deliveredUnits());
  assertEquals(0,next.delivery().regularCapacity());assertEquals(0,next.delivery().regularSoldUnits());assertTrue(next.pressure().unservedWalkIns()>0);
 }
 @Test void outageLargeMissPaysPenaltyKeepsNextMonthObligationAndTrustEcho() {
  var c=V9CapacityOrder.sign(V9CapacityOrder.Choice.LARGE);var story=V6ConflictRules.StoryState.initial();
  var first=run(state(3,MonthlyShopRules.Shock.POWER_OUTAGE,20),c,plan(V6MonthlyPlan.ProductionBand.EXPAND),story);c=V9CapacityOrder.record(c,first.delivery());
  var miss=run(first.result().game(),c,plan(V6MonthlyPlan.ProductionBand.EXPAND),first.story());
  assertEquals(2,miss.delivery().deliveredUnits());assertEquals(1400,miss.delivery().penaltyMinor());
  var after=V9CapacityOrder.afterDelivery(miss.story(),miss.delivery());assertEquals(-1,after.trust());assertEquals(2,after.echoMonths());
  c=V9CapacityOrder.record(c,miss.delivery());assertEquals(V9CapacityOrder.Status.ACTIVE,c.status());
  var last=run(miss.result().game(),c,plan(V6MonthlyPlan.ProductionBand.EXPAND),after);
  assertEquals(4,last.delivery().reservedUnits());assertEquals(0,last.delivery().bonusMinor());
  assertEquals(-1,last.story().trust());assertEquals(1,last.story().echoMonths());
  c=V9CapacityOrder.record(c,last.delivery());assertEquals(V9CapacityOrder.Status.BREACHED,c.status());assertNull(V9CapacityOrder.reservation(c,7));
 }
 @Test void conservativeChoiceCanMissLargeOrderAndPenaltyCannotCreateDebt() {
  var c=V9CapacityOrder.sign(V9CapacityOrder.Choice.LARGE);var r=run(state(3,MonthlyShopRules.Shock.NONE,0),c,plan(V6MonthlyPlan.ProductionBand.CONSERVATIVE),V6ConflictRules.StoryState.initial());
  assertEquals(2,r.delivery().deliveredUnits());assertEquals(1400,r.delivery().penaltyMinor());
  var poor=new MonthlyShopRules.State(12,3,null,2100,List.of(),List.of(),new MonthlyShopRules.Environment(MonthlyShopRules.Shock.NONE,5,5),MonthlyShopRules.Ending.RUNNING);
  var x=ledger.advancePressureMonth(poor,new MonthlyShopRules.Plan(0,16,0),MonthlyShopRules.TradingAdjustment.NONE,V9CapacityOrder.reservation(c,4));
  assertEquals(100,x.delivery().penaltyMinor());assertEquals(0,x.game().cashMinor());
 }
 @Test void oldRulesKeepTheirQuantityAndCapacity() {
  assertEquals(4,plan(V6MonthlyPlan.ProductionBand.EXPAND).strategy(signed).produceUnits());
  var old=ledger.advanceCapacityMonth(state(4,MonthlyShopRules.Shock.NONE,20),new MonthlyShopRules.Plan(6,12,0),MonthlyShopRules.TradingAdjustment.NONE,null);
  assertNull(old.pressure());assertEquals(6,old.game().reports().get(0).soldUnits());
 }
}
