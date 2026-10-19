package com.ai.repo.playground.rules;

/** Rule 1.2: an unresolved hazard still happens. No fallback signs a contract or
 * invents agreement. All alternatives replay the same immutable month and seed. */
public final class V8NegotiationStakes {
    private final V6ConflictRules conflicts = new V6ConflictRules();
    private final V5ShopRules business = new V5ShopRules();
    public record Outcome(V5ShopRules.MonthResult result, V6ConflictRules.StoryState story,MonthlyShopRules.CapacityDelivery delivery,V10CapacityPressure.Evidence pressure) {}
    public record Accounts(long cashMinor, long profitMinor, long inventoryMinor, int soldUnits,
                           MonthlyShopRules.Ending ending, boolean opened) {}
    public record Impact(String basis, Accounts actual, Accounts signedFallback,
                         Accounts firstPosition, Accounts secondPosition) {}

    /** Physical effects of inaction, not an automatic purchase, order or new agreement. */
    public V6ConflictRules.Option unresolved(V6ConflictRules.Kind kind) {
        if (kind == null) return null;
        int expense = 0, unitCost = 0, lost = 0, trust = 0, supply = 0, rent = 0;
        switch (kind) {
            case PRICE_COMPETITOR, EQUIPMENT_FAILURE, EXTREME_WEATHER, ROAD_CLOSURE, STAFF_ABSENCE -> lost = 2;
            case SUPPLIER_SHORTAGE -> { lost = 2; supply = -1; }
            case RENT_INCREASE -> { expense = 8; rent = 8; }
            case COMMUNITY_COMPLAINT, QUALITY_DOUBT -> { lost = 2; trust = -1; }
            case MATERIAL_PRICE_RISE -> unitCost = 3;
            case CUSTOMER_NO_SHOW -> lost = 1;
            case LANDLORD_RECLAIM -> { expense = 8; lost = 2; }
            case UNEXPECTED_DEMAND, RUSH_BULK_ORDER, COMMUNITY_EVENT, EXCLUSIVE_SUPPLIER -> { /* opportunity expires */ }
        }
        return new V6ConflictRules.Option("UNRESOLVED_CONSTRAINT", expense, unitCost, lost, 0, trust, supply, rent);
    }

    public Outcome advance(MonthlyShopRules.State before, V5ShopRules.Strategy signed,
            V5ShopRules.Signal signal, V5FranchiseOffer franchise, V6ConflictRules.StoryState story,
            V6ConflictRules.Kind conflict, V6MonthlyPlan plan) {
        return advance(before,signed,signal,franchise,story,conflict,plan,null,false);
    }
    public Outcome advance(MonthlyShopRules.State before, V5ShopRules.Strategy signed,
            V5ShopRules.Signal signal, V5FranchiseOffer franchise, V6ConflictRules.StoryState story,
            V6ConflictRules.Kind conflict, V6MonthlyPlan plan,MonthlyShopRules.CapacityReservation reservation,boolean boundedCapacity) {
        return advance(before,signed,signal,franchise,story,conflict,plan,reservation,boundedCapacity,false);
    }
    public Outcome advance(MonthlyShopRules.State before,V5ShopRules.Strategy signed,
            V5ShopRules.Signal signal,V5FranchiseOffer franchise,V6ConflictRules.StoryState story,
            V6ConflictRules.Kind conflict,V6MonthlyPlan plan,MonthlyShopRules.CapacityReservation reservation,
            boolean boundedCapacity,boolean pressure) {
        if (plan != null) plan.validateFor(conflict);
        MonthlyShopRules.TradingAdjustment adjustment;
        V6ConflictRules.StoryState next;
        if (conflict != null && plan != null) {
            var resolved = conflicts.resolve(conflict, plan.incidentResponse(), story);
            adjustment = resolved.immediate(); next = resolved.next();
        } else if (conflict != null) {
            var effect = unresolved(conflict);
            adjustment = new MonthlyShopRules.TradingAdjustment(effect.expenseCoins() * 100L,
                    effect.unitCostCoins() * 100L, java.util.List.of(), effect.lostBuyers(),
                    "UNRESOLVED_INCIDENT", java.util.List.of(conflict.name()));
            next = new V6ConflictRules.StoryState(Math.max(-2, story.trust() + effect.trustDelta()),
                    Math.max(-2, story.supply() + effect.supplyDelta()), Math.min(30,story.rentSurchargeCoins()+effect.rentDeltaCoins()), 2, conflict);
        } else {
            var echo = conflicts.advanceEcho(story);
            adjustment = echo.adjustment(); next = echo.next();
        }
        if (conflict != null) {
            // A fresh crisis must not erase the still-active costs of earlier choices.
            var prior = conflicts.advanceEcho(story).adjustment();
            var buyers = new java.util.ArrayList<Integer>(adjustment.extraBuyerWillingnessCoins());
            buyers.addAll(prior.extraBuyerWillingnessCoins());
            adjustment = new MonthlyShopRules.TradingAdjustment(
                    adjustment.extraExpenseMinor()+prior.extraExpenseMinor(),
                    adjustment.extraUnitCostMinor()+prior.extraUnitCostMinor(),
                    buyers.stream().limit(3).toList(),Math.min(4,adjustment.lostBuyers()+prior.lostBuyers()),
                    adjustment.eventCode(),adjustment.marketEvents());
        }
        // Option economics come from the card, never its list position. In 1.0/1.1
        // the second/third option also applied generic promotion/pivot fees and prices.
        var trade = pressure?business.advanceWithPressure(before,V10CapacityPressure.strategy(plan,signed),signal,
                V5ShopRules.Response.KEEP_IDENTITY,franchise,true,adjustment,reservation):boundedCapacity?business.advanceWithCapacity(before, plan == null ? signed : plan.strategy(signed), signal,
                V5ShopRules.Response.KEEP_IDENTITY, franchise, true, adjustment,reservation):new V5ShopRules.CapacityMonth(business.advance(before,plan==null?signed:plan.strategy(signed),signal,
                V5ShopRules.Response.KEEP_IDENTITY,franchise,true,adjustment),null);
        return new Outcome(trade.month(), next,trade.delivery(),trade.pressure());
    }

    public Accounts accounts(MonthlyShopRules.State before, V5ShopRules.MonthResult result) {
        var game = result.game();
        boolean opened = game.operatedMonths() > before.operatedMonths();
        long inventory = game.inventory().stream().mapToLong(batch -> batch.units()*batch.unitCostMinor()).sum();
        long oldInventory = before.inventory().stream().mapToLong(batch -> batch.units()*batch.unitCostMinor()).sum();
        int sold = opened ? game.reports().get(game.reports().size()-1).soldUnits() : 0;
        return new Accounts(game.cashMinor(), game.cashMinor()+inventory-before.cashMinor()-oldInventory,
                inventory, sold, game.ending(), opened);
    }

    public Impact impact(MonthlyShopRules.State before, V5ShopRules.Strategy signed,
            V5ShopRules.Signal signal, V5FranchiseOffer franchise, V6ConflictRules.StoryState story,
            V6ConflictRules.Kind conflict, V6MonthlyPlan first, V6MonthlyPlan second,
            V5ShopRules.MonthResult actual) {
        return impact(before,signed,signal,franchise,story,conflict,first,second,actual,null,false);
    }
    public Impact impact(MonthlyShopRules.State before, V5ShopRules.Strategy signed,
            V5ShopRules.Signal signal, V5FranchiseOffer franchise, V6ConflictRules.StoryState story,
            V6ConflictRules.Kind conflict, V6MonthlyPlan first, V6MonthlyPlan second,
            V5ShopRules.MonthResult actual,MonthlyShopRules.CapacityReservation reservation,boolean boundedCapacity) {
        return impact(before,signed,signal,franchise,story,conflict,first,second,actual,reservation,boundedCapacity,false);
    }
    public Impact impact(MonthlyShopRules.State before,V5ShopRules.Strategy signed,V5ShopRules.Signal signal,
            V5FranchiseOffer franchise,V6ConflictRules.StoryState story,V6ConflictRules.Kind conflict,
            V6MonthlyPlan first,V6MonthlyPlan second,V5ShopRules.MonthResult actual,
            MonthlyShopRules.CapacityReservation reservation,boolean boundedCapacity,boolean pressure) {
        return new Impact("SAME_MONTH_SAME_MARKET", accounts(before, actual),
                accounts(before, advance(before, signed, signal, franchise, story, conflict, null,reservation,boundedCapacity,pressure).result()),
                first == null ? null : accounts(before, advance(before, signed, signal, franchise, story, conflict, first,reservation,boundedCapacity,pressure).result()),
                second == null ? null : accounts(before, advance(before, signed, signal, franchise, story, conflict, second,reservation,boundedCapacity,pressure).result()));
    }
}
