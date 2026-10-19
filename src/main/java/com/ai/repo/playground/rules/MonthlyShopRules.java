package com.ai.repo.playground.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Versioned virtual-month reducer. Amounts are minor units, never real money.
 * Authorization, mutually accepted plans and task transactions belong to the service layer.
 */
public final class MonthlyShopRules {
    public static final String RULE_VERSION = "0.4";
    public static final long CAPITAL = 20_000;
    public enum Ending { RUNNING, YEAR_COMPLETE, SHORT_COMPLETE, ACTIVE_CLOSURE, BUSINESS_FAILURE }
    public enum Shock { NONE, MARKET_SLOWDOWN, MATERIAL_SURGE, RENT_RENEWAL, PACKAGING_RULE, POWER_OUTAGE }
    public record Environment(Shock shock, int fromMonth, int throughMonth, int demandSeed) {
        public Environment(Shock shock, int fromMonth, int throughMonth) {
            this(shock, fromMonth, throughMonth, 0);
        }
        public Environment {
            require(shock != null && fromMonth >= 1 && throughMonth >= fromMonth && throughMonth <= 12
                    && demandSeed >= 0,
                    "INVALID_ENVIRONMENT");
        }
        boolean active(int month, Shock kind) {
            return shock == kind && month >= fromMonth && month <= throughMonth;
        }
    }
    public record Plan(int produceUnits, int unitPriceCoins, long reserveMinor) {
        public Plan {
            require(produceUnits >= 0 && produceUnits <= 6, "INVALID_PRODUCTION");
            require(List.of(12, 16, 20).contains(unitPriceCoins), "INVALID_PRICE");
            require(reserveMinor >= 0 && reserveMinor <= CAPITAL, "INVALID_RESERVE");
        }
    }
    /** A pre-approved, all-or-nothing NPC order due in one virtual month. */
    public record NpcOrder(String id, int dueMonth, int quantity, int unitPriceCoins, int npcBudgetCoins) {
        public NpcOrder {
            require(id != null && id.matches("[A-Za-z0-9:_-]{1,100}"), "INVALID_ORDER_ID");
            require(dueMonth >= 1 && dueMonth <= 12 && quantity >= 1 && quantity <= 6
                    && unitPriceCoins >= 12 && unitPriceCoins <= 34, "INVALID_ORDER_TERMS");
            require(npcBudgetCoins >= quantity * unitPriceCoins, "NPC_BUDGET_EXCEEDED");
        }
    }
    /** Server-selected v5 effects. Never deserialize model-supplied amounts into this record. */
    public record TradingAdjustment(long extraExpenseMinor, long extraUnitCostMinor,
                                    List<Integer> extraBuyerWillingnessCoins, int lostBuyers,
                                    String eventCode, List<String> marketEvents,
                                    List<Integer> baseBuyerWillingnessCoins) {
        public TradingAdjustment(long extraExpenseMinor, long extraUnitCostMinor,
                                 List<Integer> extraBuyerWillingnessCoins, int lostBuyers,
                                 String eventCode) {
            this(extraExpenseMinor,extraUnitCostMinor,extraBuyerWillingnessCoins,lostBuyers,eventCode,List.of(),List.of());
        }
        public TradingAdjustment(long extraExpenseMinor, long extraUnitCostMinor,
                                 List<Integer> extraBuyerWillingnessCoins, int lostBuyers,
                                 String eventCode, List<String> marketEvents) {
            this(extraExpenseMinor,extraUnitCostMinor,extraBuyerWillingnessCoins,lostBuyers,eventCode,marketEvents,List.of());
        }
        public static final TradingAdjustment NONE = new TradingAdjustment(0, 0, List.of(), 0, null);
        public TradingAdjustment {
            require(extraExpenseMinor >= 0 && extraExpenseMinor <= 10_000
                    && extraUnitCostMinor >= 0 && extraUnitCostMinor <= 1_200
                    && extraBuyerWillingnessCoins != null && extraBuyerWillingnessCoins.size() <= 3
                    && extraBuyerWillingnessCoins.stream().allMatch(price -> price != null && price >= 12 && price <= 34)
                    && lostBuyers >= 0 && lostBuyers <= 4
                    && (eventCode == null || eventCode.matches("[A-Z0-9_]{1,40}"))
                    && marketEvents != null && marketEvents.size() <= 2
                    && marketEvents.stream().allMatch(code -> code != null && code.matches("[A-Z0-9_]{1,40}"))
                    && baseBuyerWillingnessCoins != null
                    && (baseBuyerWillingnessCoins.isEmpty() || baseBuyerWillingnessCoins.size()==6)
                    && baseBuyerWillingnessCoins.stream().allMatch(price -> price != null && price >= 10 && price <= 34),
                    "INVALID_TRADING_ADJUSTMENT");
            extraBuyerWillingnessCoins = List.copyOf(extraBuyerWillingnessCoins);
            marketEvents = List.copyOf(marketEvents);
            baseBuyerWillingnessCoins = List.copyOf(baseBuyerWillingnessCoins);
        }
    }
    public record CapacityReservation(int units,int unitPriceCoins,long missedPenaltyMinor,long completionBonusMinor) {
        public CapacityReservation {
            require(units>=1 && units<=4 && unitPriceCoins>=12 && unitPriceCoins<=20
                    && missedPenaltyMinor>=0 && missedPenaltyMinor<=2000
                    && completionBonusMinor>=0 && completionBonusMinor<=2000,"INVALID_CAPACITY_RESERVATION");
        }
    }
    public record CapacityDelivery(int month,int reservedUnits,int deliveredUnits,int regularCapacity,
            int regularSoldUnits,long revenueMinor,long penaltyMinor,long bonusMinor,boolean fulfilled) {}
    public record CapacityTrade(State game,CapacityDelivery delivery,V10CapacityPressure.Evidence pressure) {
        public CapacityTrade(State game,CapacityDelivery delivery) { this(game,delivery,null); }
    }
    public record Batch(int units, long unitCostMinor) {}
    public record MonthlyReport(int month, long openingCashMinor, long closingCashMinor,
                                long receiptsMinor, long paymentsMinor, long salesMinor,
                                long costOfGoodsSoldMinor, long operatingExpenseMinor,
                                long openingInventoryMinor, long closingInventoryMinor,
                                long liquidationReceiptsMinor, long liquidationBookCostMinor,
                                long profitMinor, long cumulativeProfitMinor, int soldUnits,
                                List<String> events) {
        public MonthlyReport { events = List.copyOf(events); }
    }
    public record State(int horizonMonths, int operatedMonths, Integer failedOpeningMonth,
                        long cashMinor, List<Batch> inventory, List<MonthlyReport> reports,
                        Environment environment, Ending ending) {
        public State { inventory = List.copyOf(inventory); reports = List.copyOf(reports); }
    }
    public record Summary(Ending ending, int operatedMonths, Integer failedOpeningMonth,
                          long returnedCapitalMinor, long netProfitMinor,
                          long ownerOneReturnedMinor, long ownerTwoReturnedMinor,
                          List<MonthlyReport> reports) {
        public Summary { reports = List.copyOf(reports); }
    }
    public State initialize(int horizonMonths, Environment environment) {
        require((horizonMonths == 2 || horizonMonths == 12) && environment != null, "INVALID_GAME");
        return new State(horizonMonths, 0, null, CAPITAL, List.of(), List.of(), environment, Ending.RUNNING);
    }

    /** Draw once at opening and persist the resulting environment with the room state. */
    public Environment drawEnvironment(int horizonMonths, RandomGenerator random) {
        require((horizonMonths == 2 || horizonMonths == 12) && random != null, "INVALID_GAME");
        Shock shock = Shock.values()[1 + random.nextInt(Shock.values().length - 1)];
        int month = horizonMonths == 2 ? 2 : 2 + random.nextInt(3);
        return new Environment(shock, month, Math.min(horizonMonths, month + (horizonMonths == 12 ? 1 : 0)));
    }

    /** One approved standing plan executes one virtual month, without a model call.
     * All prices/costs are coarse game parameters rather than a thirty-day simulation.
     */
    public State advanceMonth(State state, Plan plan) {
        return advanceMonth(state, plan, null);
    }

    /** The optional order must have been approved by both partners and the NPC before this call. */
    public State advanceMonth(State state, Plan plan, NpcOrder order) {
        return advanceMonth(state, plan, order, TradingAdjustment.NONE);
    }

    /** V5-only controlled effects share the same inventory, cash and month ledger as legacy trading. */
    public State advanceMonth(State state, Plan plan, NpcOrder order, TradingAdjustment adjustment) {
        return trade(state,plan,order,adjustment,null,false).game();
    }
    public CapacityTrade advanceCapacityMonth(State state,Plan plan,TradingAdjustment adjustment,CapacityReservation reservation) {
        return trade(state,plan,null,adjustment,reservation,true);
    }
    public CapacityTrade advancePressureMonth(State state,Plan plan,TradingAdjustment adjustment,CapacityReservation reservation) {
        return trade(state,plan,null,adjustment,reservation,true,true);
    }
    private CapacityTrade trade(State state,Plan plan,NpcOrder order,TradingAdjustment adjustment,CapacityReservation reservation,boolean boundedCapacity) {
        return trade(state,plan,order,adjustment,reservation,boundedCapacity,false);
    }
    private CapacityTrade trade(State state,Plan plan,NpcOrder order,TradingAdjustment adjustment,CapacityReservation reservation,boolean boundedCapacity,boolean pressure) {
        running(state);
        require(plan != null && adjustment != null, "INVALID_PLAN");
        int month = state.operatedMonths() + 1;
        require(order == null || order.dueMonth() == month, "ORDER_MONTH_MISMATCH");
        Environment env = state.environment();
        long fixed = (env.active(month, Shock.RENT_RENEWAL) ? 28 : 20) * 100L
                + (month == 1 ? 4_000 : 0) + adjustment.extraExpenseMinor();
        // Failure is an inability to open after liquidating inventory, not a negative monthly profit.
        if (state.cashMinor() < fixed) {
            State closed = liquidate(state, Ending.BUSINESS_FAILURE, month);
            if (closed.cashMinor() < fixed) return new CapacityTrade(closed,null);
            state = new State(closed.horizonMonths(), closed.operatedMonths(), null, closed.cashMinor(),
                    closed.inventory(), closed.reports(), env, Ending.RUNNING);
        }
        long openingCash = state.cashMinor();
        long openingStock = inventoryValue(state.inventory());
        long cash = openingCash - fixed;
        long payments = fixed;
        long receipts = 0, sales = 0, cogs = 0, expense = fixed;
        int capacity = env.active(month, Shock.POWER_OUTAGE) ? 2 : 6;
        if(pressure) capacity=V10CapacityPressure.capacity(month,capacity);
        long unitCost = (env.active(month, Shock.MATERIAL_SURGE) ? 900 : 600)
                + adjustment.extraUnitCostMinor();
        List<Batch> stock = new ArrayList<>(state.inventory());
        List<String> events = new ArrayList<>();
        if (env.shock() != Shock.NONE && month >= env.fromMonth() && month <= env.throughMonth())
            events.add(env.shock().name());
        if (adjustment.eventCode() != null) events.add(adjustment.eventCode());
        events.addAll(adjustment.marketEvents());
        int requested = Math.min(plan.produceUnits(), capacity);
        int produced = (int) Math.min(requested, Math.max(0, cash - plan.reserveMinor()) / unitCost);
        if (produced > 0) {
            stock.add(new Batch(produced, unitCost));
            cash -= produced * unitCost;
            payments += produced * unitCost;
        }
        if (produced < plan.produceUnits()) events.add("PRODUCTION_REDUCED");
        int sold = 0;
        if (order != null) {
            int available = stock.stream().mapToInt(Batch::units).sum();
            long packaging = env.active(month, Shock.PACKAGING_RULE) ? order.quantity() * 200L : 0;
            if (available >= order.quantity() && cash >= packaging) {
                for (int i = 0; i < order.quantity(); i++) {
                    Batch batch = stock.remove(0);
                    if (batch.units() > 1) stock.add(0, new Batch(batch.units() - 1, batch.unitCostMinor()));
                    cogs += batch.unitCostMinor();
                }
                long orderSales = order.quantity() * order.unitPriceCoins() * 100L;
                sales += orderSales; receipts += orderSales; cash += orderSales - packaging;
                payments += packaging; expense += packaging; sold += order.quantity();
                events.add("NPC_ORDER_FULFILLED");
            } else events.add("NPC_ORDER_UNFILLED");
        }
        int availableUnits=stock.stream().mapToInt(Batch::units).sum();
        int contractSold=0;
        long orderRevenue=0;
        int regularCapacity=!boundedCapacity?Integer.MAX_VALUE:Math.max(0,capacity-(reservation==null?0:reservation.units()));
        if (reservation!=null) {
            int available=stock.stream().mapToInt(Batch::units).sum();
            int deliver=Math.min(reservation.units(),Math.min(capacity,available));
            for(int i=0;i<deliver;i++) {
                long packaging=env.active(month,Shock.PACKAGING_RULE)?200:0;
                if(cash<packaging) break;
                Batch batch=stock.remove(0);
                if(batch.units()>1) stock.add(0,new Batch(batch.units()-1,batch.unitCostMinor()));
                cogs+=batch.unitCostMinor();
                long sale=reservation.unitPriceCoins()*100L;
                cash+=sale-packaging; sales+=sale;receipts+=sale;payments+=packaging;expense+=packaging;
                contractSold++;sold++;orderRevenue+=sale;
            }
            events.add(contractSold==reservation.units()?"CAPACITY_ORDER_FULFILLED":"CAPACITY_ORDER_MISSED");
        }
        int regularSold=0;
        List<Integer> pool = !adjustment.baseBuyerWillingnessCoins().isEmpty()
                ? adjustment.baseBuyerWillingnessCoins()
                : env.active(month, Shock.MARKET_SLOWDOWN)
                ? List.of(10, 10, 12, 12, 20, 26) : List.of(12, 12, 16, 16, 28, 34);
        List<Integer> buyers = new ArrayList<>();
        for (int i = 0; i < pool.size() - adjustment.lostBuyers(); i++) buyers.add(pool.get(i));
        buyers.addAll(adjustment.extraBuyerWillingnessCoins());
        int walkInDemand=(int)buyers.stream().filter(w->w>=plan.unitPriceCoins()).count();
        int stockAfterOrder=stock.stream().mapToInt(Batch::units).sum();
        for (int willingness : buyers) {
            if(regularSold>=regularCapacity) break;
            long packaging = env.active(month, Shock.PACKAGING_RULE) ? 200 : 0;
            if (willingness < plan.unitPriceCoins() || stock.isEmpty() || cash < packaging) continue;
            Batch batch = stock.remove(0);
            if (batch.units() > 1) stock.add(0, new Batch(batch.units() - 1, batch.unitCostMinor()));
            cogs += batch.unitCostMinor();
            long sale = plan.unitPriceCoins() * 100L;
            sales += sale; receipts += sale; cash += sale - packaging;
            payments += packaging; expense += packaging; sold++;regularSold++;
        }
        long penalty=0,bonus=0;
        if(reservation!=null) {
            if(contractSold<reservation.units()) {
                penalty=Math.min(cash,reservation.missedPenaltyMinor());
                cash-=penalty;payments+=penalty;expense+=penalty;
            } else {
                bonus=reservation.completionBonusMinor();
                cash+=bonus;receipts+=bonus;sales+=bonus;
            }
        }
        long closingStock = inventoryValue(stock);
        long profit = sales - cogs - expense;
        List<MonthlyReport> reports = new ArrayList<>(state.reports());
        reports.add(new MonthlyReport(month, openingCash, cash, receipts, payments, sales, cogs, expense,
                openingStock, closingStock, 0, 0, profit, cash + closingStock - CAPITAL, sold, events));
        State next = new State(state.horizonMonths(), month, null, cash, stock, reports, env, Ending.RUNNING);
        if (month == state.horizonMonths())
            next=liquidate(next, month == 12 ? Ending.YEAR_COMPLETE : Ending.SHORT_COMPLETE, null);
        return new CapacityTrade(next,reservation==null?null:new CapacityDelivery(month,reservation.units(),
                contractSold,regularCapacity,regularSold,orderRevenue,penalty,bonus,contractSold==reservation.units()),!pressure?null:new V10CapacityPressure.Evidence(month,capacity,produced,availableUnits,
                walkInDemand,reservation==null?0:reservation.units(),contractSold,regularCapacity,regularSold,
                walkInDemand-regularSold,Math.max(0,walkInDemand+(reservation==null?0:reservation.units())-capacity),
                Math.max(0,Math.min(walkInDemand,regularCapacity)-stockAfterOrder)));
    }
    public State close(State state) { running(state); return liquidate(state, Ending.ACTIVE_CLOSURE, null); }
    public Summary summary(State state) {
        require(state.ending() != Ending.RUNNING, "GAME_NOT_FINISHED");
        long first = state.cashMinor() / 2;
        return new Summary(state.ending(), state.operatedMonths(), state.failedOpeningMonth(), state.cashMinor(),
                state.cashMinor() - CAPITAL, first, state.cashMinor() - first, state.reports());
    }
    /** V6 signed terms replace the legacy equal payout without changing the shared ledger. */
    public Summary summary(State state, V6FoundingAgreement agreement) {
        require(agreement != null, "INVALID_FOUNDING_AGREEMENT");
        require(state.ending() != Ending.RUNNING, "GAME_NOT_FINISHED");
        V6FoundingAgreement.Allocation allocation = agreement.allocate(state.cashMinor());
        return new Summary(state.ending(), state.operatedMonths(), state.failedOpeningMonth(),
                state.cashMinor(), allocation.netProfitMinor(), allocation.hostReturnedMinor(),
                allocation.guestReturnedMinor(), state.reports());
    }
    private State liquidate(State state, Ending ending, Integer failedOpeningMonth) {
        long book = inventoryValue(state.inventory());
        long recovery = state.inventory().stream().mapToLong(b -> b.units() * (b.unitCostMinor() / 200) * 100).sum();
        List<MonthlyReport> reports = new ArrayList<>(state.reports());
        if (!reports.isEmpty() && book > 0) {
            MonthlyReport r = reports.remove(reports.size() - 1);
            List<String> events = new ArrayList<>(r.events()); events.add("INVENTORY_LIQUIDATED");
            reports.add(new MonthlyReport(r.month(), r.openingCashMinor(), r.closingCashMinor() + recovery,
                    r.receiptsMinor() + recovery, r.paymentsMinor(), r.salesMinor(), r.costOfGoodsSoldMinor(),
                    r.operatingExpenseMinor(), r.openingInventoryMinor(), 0,
                    r.liquidationReceiptsMinor() + recovery, r.liquidationBookCostMinor() + book,
                    r.profitMinor() + recovery - book, state.cashMinor() + recovery - CAPITAL, r.soldUnits(), events));
        }
        return new State(state.horizonMonths(), state.operatedMonths(), failedOpeningMonth,
                state.cashMinor() + recovery, List.of(), reports, state.environment(), ending);
    }
    private static long inventoryValue(List<Batch> batches) {
        return batches.stream().mapToLong(b -> b.units() * b.unitCostMinor()).sum();
    }
    private static void running(State state) {
        require(state != null && state.ending() == Ending.RUNNING && state.operatedMonths() < state.horizonMonths(),
                "GAME_FINISHED");
    }
    private static void require(boolean condition, String error) {
        if (!condition) throw new IllegalArgumentException(error);
    }
}
