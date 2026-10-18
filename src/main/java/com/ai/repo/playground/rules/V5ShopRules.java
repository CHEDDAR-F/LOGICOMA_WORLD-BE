package com.ai.repo.playground.rules;

import java.util.ArrayList;
import java.util.List;

/** Pure, bounded v5 strategy economics. A room service must persist the signed strategy,
 * signal and both Agents' decisions before invoking this reducer.
 */
public final class V5ShopRules {
    public enum Audience { NIGHT_READERS, COMMUTERS, STUDENTS, NEIGHBORS, FAMILIES, PET_OWNERS, HOBBYISTS }
    public enum Channel { NONE, FLYERS, LOCAL_EVENT }
    public enum ServicePromise { QUIET, FAST, COMMUNITY }
    public enum Signal { NORMAL, MARKET_SHIFT, MATERIAL_SURGE, RENT_RISE, PACKAGING_CHANGE, POWER_OUTAGE, COMPETITOR, SUPPLY_DELAY }
    public enum Response { KEEP_IDENTITY, PROMOTE, TEMPORARY_PIVOT }

    public record Strategy(int produceUnits, int unitPriceCoins, long minimumReserveMinor,
                           Audience audience, Channel channel, ServicePromise servicePromise,
                           long monthlyMarketingBudgetMinor) {
        public Strategy {
            new MonthlyShopRules.Plan(produceUnits, unitPriceCoins, minimumReserveMinor);
            require(audience != null && channel != null && servicePromise != null,
                    "INVALID_V5_STRATEGY");
            require(monthlyMarketingBudgetMinor >= 0 && monthlyMarketingBudgetMinor <= 1_200
                    && monthlyMarketingBudgetMinor % 300 == 0, "INVALID_V5_STRATEGY");
            require((channel == Channel.NONE) == (monthlyMarketingBudgetMinor == 0),
                    "INVALID_V5_STRATEGY");
            if (channel == Channel.LOCAL_EVENT)
                require(monthlyMarketingBudgetMinor >= 600, "INVALID_V5_STRATEGY");
        }
    }

    public record MonthResult(MonthlyShopRules.State game, Response response,
                              long marketingSpentMinor, int addedBuyers,
                              boolean budgetGuardTriggered) {}

    private final MonthlyShopRules ledger = new MonthlyShopRules();

    /** Only the server chooses the signal and this method's finite response values.
     * All cost and demand changes are deterministic; model prose cannot modify them.
     */
    public MonthResult advance(MonthlyShopRules.State game, Strategy strategy,
                               Signal signal, Response response) {
        return advance(game,strategy,signal,response,null);
    }

    /** The signed NPC contract is a server-owned obligation, never a model amount. */
    public MonthResult advance(MonthlyShopRules.State game, Strategy strategy,
                               Signal signal, Response response,V5FranchiseOffer franchise) {
        return advance(game,strategy,signal,response,franchise,false);
    }

    /** Rule 0.7 adds a room-seeded footfall pulse. Old rooms retain their fixed demand curve. */
    public MonthResult advance(MonthlyShopRules.State game, Strategy strategy,
                               Signal signal, Response response,V5FranchiseOffer franchise,
                               boolean variableDemand) {
        return advance(game,strategy,signal,response,franchise,variableDemand,
                MonthlyShopRules.TradingAdjustment.NONE);
    }

    /** Rule 0.9 overlays one server-selected conflict or a persisted echo. */
    public MonthResult advance(MonthlyShopRules.State game, Strategy strategy,
                               Signal signal, Response response,V5FranchiseOffer franchise,
                               boolean variableDemand,MonthlyShopRules.TradingAdjustment external) {
        require(game != null && strategy != null && signal != null && response != null,
                "INVALID_V5_MONTH");
        require(external != null,"INVALID_V5_MONTH");
        int month = game.operatedMonths() + 1;
        require(month <= game.horizonMonths(), "GAME_FINISHED");
        MonthlyShopRules.Shock expected = switch (signal) {
            case MARKET_SHIFT -> MonthlyShopRules.Shock.MARKET_SLOWDOWN;
            case MATERIAL_SURGE -> MonthlyShopRules.Shock.MATERIAL_SURGE;
            case RENT_RISE -> MonthlyShopRules.Shock.RENT_RENEWAL;
            case PACKAGING_CHANGE -> MonthlyShopRules.Shock.PACKAGING_RULE;
            case POWER_OUTAGE -> MonthlyShopRules.Shock.POWER_OUTAGE;
            case NORMAL, COMPETITOR, SUPPLY_DELAY -> MonthlyShopRules.Shock.NONE;
        };
        MonthlyShopRules.Environment environment = game.environment();
        MonthlyShopRules.Shock active = month >= environment.fromMonth() && month <= environment.throughMonth()
                ? environment.shock() : MonthlyShopRules.Shock.NONE;
        require(active == expected, "SIGNAL_ENVIRONMENT_MISMATCH");
        require(signal != Signal.NORMAL || external.eventCode()!=null || response == Response.KEEP_IDENTITY,
                "DECISION_WITHOUT_SIGNAL");
        require(response != Response.PROMOTE || strategy.channel() != Channel.NONE
                        || external.eventCode()!=null,
                "PROMOTION_CHANNEL_REQUIRED");

        long essential = (active == MonthlyShopRules.Shock.RENT_RENEWAL ? 2_800 : 2_000)
                + (month == 1 ? 4_000 : 0) + external.extraExpenseMinor();
        long franchiseExpense=franchise==null?0:franchise.entryCost(month)+franchise.monthlyCost(month);
        long standingCost = strategy.monthlyMarketingBudgetMinor();
        boolean guard = game.cashMinor() - essential - franchiseExpense - standingCost < strategy.minimumReserveMinor();
        if (guard) standingCost = 0;
        long decisionCost = switch (response) {
            case KEEP_IDENTITY -> 0;
            case PROMOTE -> 600;
            case TEMPORARY_PIVOT -> 300;
        };
        require(game.cashMinor() - essential - franchiseExpense - standingCost - decisionCost >= strategy.minimumReserveMinor()
                || response == Response.KEEP_IDENTITY, "V5_CHOICE_UNAFFORDABLE");

        List<Integer> extraBuyers = new ArrayList<>(external.extraBuyerWillingnessCoins());
        if (!guard && standingCost > 0) extraBuyers.add(strategy.unitPriceCoins());
        // A signed audience-service fit retains one bounded customer during a market shift.
        if (signal == Signal.MARKET_SHIFT && (
                (strategy.audience() == Audience.NIGHT_READERS
                        && strategy.servicePromise() == ServicePromise.QUIET)
                || (variableDemand && audiencePromiseFit(strategy)))) extraBuyers.add(20);
        if (response == Response.PROMOTE) {
            extraBuyers.add(strategy.unitPriceCoins());
            if (extraBuyers.size() < 3) extraBuyers.add(strategy.unitPriceCoins());
        }
        if (response == Response.TEMPORARY_PIVOT && extraBuyers.size() < 3) extraBuyers.add(12);
        if (franchise!=null && franchise.supportBuyers(month)>0 && extraBuyers.size()<3)
            extraBuyers.add(strategy.unitPriceCoins());
        int lostBuyers = (signal == Signal.COMPETITOR ? 2 : signal == Signal.SUPPLY_DELAY ? 1 : 0)
                + external.lostBuyers();
        List<String> marketEvents = new ArrayList<>(external.marketEvents());
        if (signal == Signal.SUPPLY_DELAY && marketEvents.size()<2) marketEvents.add("SUPPLY_DELAY");
        if (variableDemand) {
            int pulse = footfallPulse(game.environment().demandSeed(),month);
            if (pulse < 0) lostBuyers += -pulse;
            else for (int i=0;i<pulse && extraBuyers.size()<3;i++)
                extraBuyers.add(strategy.unitPriceCoins());
            if (marketEvents.size()<2) {
                if (pulse <= -2) marketEvents.add("QUIET_STREET");
                else if (pulse == -1) marketEvents.add("SLOW_WEEK");
                else if (pulse == 1) marketEvents.add("NEIGHBORHOOD_BUZZ");
                else if (pulse >= 2) marketEvents.add("LOCAL_RUSH");
            }
        }
        if (extraBuyers.size() > 3) extraBuyers = new ArrayList<>(extraBuyers.subList(0, 3));
        int price = response == Response.TEMPORARY_PIVOT ? 12 : strategy.unitPriceCoins();
        int production = signal == Signal.MARKET_SHIFT && response == Response.KEEP_IDENTITY
                ? Math.max(0, strategy.produceUnits() - 1) : strategy.produceUnits();
        // A missed delivery limits stock. Paying for a temporary pivot secures
        // one replacement unit, so the two Agents' response changes the ledger.
        if (signal == Signal.SUPPLY_DELAY) production = Math.max(0,production
                - (response == Response.TEMPORARY_PIVOT ? 1 : 2));
        MonthlyShopRules.Plan plan = new MonthlyShopRules.Plan(production, price, strategy.minimumReserveMinor());
        MonthlyShopRules.TradingAdjustment adjustment = new MonthlyShopRules.TradingAdjustment(
                standingCost + decisionCost + franchiseExpense + external.extraExpenseMinor(),
                (franchise==null?0:franchise.unitPremium(month))
                        + (signal==Signal.SUPPLY_DELAY ? 200 : 0) + external.extraUnitCostMinor(),extraBuyers,
                Math.min(variableDemand?4:2,lostBuyers+(franchise==null?0:franchise.lostBuyers(month))),
                external.eventCode()!=null?external.eventCode():
                        franchise==null?"V5_" + response.name():franchise.resultEvent(month),marketEvents,
                variableDemand
                        ? (signal==Signal.MARKET_SHIFT
                            ? List.of(10,10,12,12,14,26) : List.of(10,12,14,15,28,34))
                        : List.of());
        MonthlyShopRules.State next = ledger.advanceMonth(game, plan, null, adjustment);
        boolean failedToOpen = next.failedOpeningMonth() != null;
        return new MonthResult(next, response, failedToOpen ? 0 : standingCost + decisionCost,
                failedToOpen ? 0 : extraBuyers.size(), guard);
    }

    private static int footfallPulse(int seed,int month) {
        // A midyear high and a late-year low make every full room confront both
        // inventory pressure and fixed costs; the room seed varies their timing.
        if (month==4+seed%3) return 2;
        if (month==9+(seed/3)%3) return -2;
        int mixed=seed ^ (month * 0x9e3779b9);
        mixed ^= mixed >>> 16;
        mixed *= 0x7feb352d;
        mixed ^= mixed >>> 15;
        mixed *= 0x846ca68b;
        mixed ^= mixed >>> 16;
        return switch (Math.floorMod(mixed,7)) {
            case 0 -> -2;
            case 1,2 -> -1;
            case 3,4 -> 1;
            case 5 -> 2;
            default -> 0;
        };
    }

    private static boolean audiencePromiseFit(Strategy strategy) {
        return switch (strategy.audience()) {
            case COMMUTERS, FAMILIES -> strategy.servicePromise()==ServicePromise.FAST;
            case STUDENTS, NEIGHBORS, PET_OWNERS, HOBBYISTS ->
                    strategy.servicePromise()==ServicePromise.COMMUNITY;
            case NIGHT_READERS -> false;
        };
    }

    private static void require(boolean condition, String error) {
        if (!condition) throw new IllegalArgumentException(error);
    }
}
