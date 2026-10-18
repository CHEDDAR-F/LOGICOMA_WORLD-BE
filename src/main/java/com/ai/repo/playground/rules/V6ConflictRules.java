package com.ai.repo.playground.rules;

import java.util.Collections;
import java.util.List;

/** Deterministic, server-owned conflict cards and two-month consequences.
 * Agent text can select an option code, but never supplies prices or probabilities.
 */
public final class V6ConflictRules {
    public enum Family { MARKET, SUPPLY, OPERATIONS, PROPERTY, COMMUNITY, OPPORTUNITY }
    public enum Kind {
        PRICE_COMPETITOR(Family.MARKET,
                o("KEEP_POSITION",0,0,2,0,1,0,0),o("LOYALTY_BUNDLE",6,0,1,1,1,0,0),o("PRICE_SUPPORT_COUPON",8,0,0,2,-1,0,0)),
        SUPPLIER_SHORTAGE(Family.SUPPLY,
                o("WAIT_RESTOCK",0,0,2,0,-1,-1,0),o("PAY_ALTERNATE",12,2,0,0,0,1,0),o("VERIFIED_REFURBISHED",6,1,1,1,0,0,0)),
        EQUIPMENT_FAILURE(Family.OPERATIONS,
                o("LIMIT_SERVICE",0,0,2,0,-1,0,0),o("BORROW_WORKSPACE",8,0,1,1,1,0,0),o("RENT_EQUIPMENT",16,0,0,1,0,0,0)),
        RENT_INCREASE(Family.PROPERTY,
                o("RENEW_LEASE",0,0,0,0,0,0,8),o("NEGOTIATE_SHORT_TERM",5,0,1,0,0,0,3),o("RELOCATE",25,0,2,0,1,0,-4)),
        COMMUNITY_COMPLAINT(Family.COMMUNITY,
                o("RESTRICT_CAPACITY",3,0,2,0,1,0,0),o("REMEDY_NOW",12,0,0,0,2,0,0),o("CHANGE_PROCESS",7,0,1,1,1,0,0)),
        UNEXPECTED_DEMAND(Family.OPPORTUNITY,
                o("LIMIT_BOOKINGS",0,0,1,1,1,0,0),o("TEMPORARY_PARTNER",10,1,0,2,0,-1,0),o("BUILD_STOCK",18,0,0,2,-1,-1,0)),
        QUALITY_DOUBT(Family.COMMUNITY,
                o("INSPECT_BATCH",14,0,2,0,2,0,0),o("REPLACE_AFFECTED",9,0,1,0,1,0,0),o("PAUSE_PRODUCT",5,0,2,0,0,0,0)),
        MATERIAL_PRICE_RISE(Family.SUPPLY,
                o("FIND_ALTERNATE",8,1,1,0,0,1,0),o("ACCEPT_PRICE_RISE",0,3,2,0,-1,0,0),o("TRIM_LOW_MARGIN",0,0,1,0,1,-1,0)),
        RUSH_BULK_ORDER(Family.OPPORTUNITY,
                o("LIMIT_ORDER",0,0,1,1,1,0,0),o("OVERTIME_FULFILL",14,2,0,2,0,-1,0),o("DECLINE_ORDER",0,0,1,0,0,0,0)),
        EXTREME_WEATHER(Family.OPERATIONS,
                o("CHANGE_HOURS",2,0,1,0,1,0,0),o("PROTECT_STOCK",10,0,0,1,1,0,0),o("KEEP_HOURS",0,0,2,0,-1,0,0)),
        ROAD_CLOSURE(Family.MARKET,
                o("STREET_STALL",7,0,1,1,0,0,0),o("ONLINE_BOOKING",9,0,0,1,1,0,0),o("WAIT_FOR_ACCESS",0,0,2,0,-1,0,0)),
        COMMUNITY_EVENT(Family.OPPORTUNITY,
                o("JOIN_EVENT",10,0,0,2,1,0,0),o("SMALL_COLLAB",5,0,0,1,1,0,0),o("SKIP_EVENT",0,0,0,0,0,0,0)),
        EXCLUSIVE_SUPPLIER(Family.SUPPLY,
                o("ACCEPT_EXCLUSIVE",0,0,0,1,-1,-2,0),o("NEGOTIATE_TERM",5,0,1,1,0,1,0),o("KEEP_OPTIONS",0,1,0,0,0,1,0)),
        STAFF_ABSENCE(Family.OPERATIONS,
                o("LIMIT_CAPACITY",0,0,2,0,1,0,0),o("HIRE_COVER",12,0,0,1,0,0,0),o("SIMPLIFY_SERVICE",4,0,1,1,0,0,0)),
        CUSTOMER_NO_SHOW(Family.MARKET,
                o("ALLOW_RESCHEDULE",0,0,1,0,1,0,0),o("ENFORCE_BOOKING_RULE",0,0,1,0,-1,0,0),o("FILL_WAITLIST",3,0,0,1,1,0,0)),
        LANDLORD_RECLAIM(Family.PROPERTY,
                o("NEGOTIATE_EXTENSION",8,0,1,0,0,0,5),o("MOVE_PREMISES",30,0,2,0,1,0,-5),o("POPUP_BRIDGE",12,0,1,1,0,0,0));

        private final Family family;
        private final List<Option> options;
        Kind(Family family, Option first, Option second, Option third) {
            this.family=family;this.options=List.of(first,second,third);
        }
        public Family family() { return family; }
        public List<Option> options() { return options; }
    }

    /** All amounts are game coins. State stays in the private, persisted room. */
    public record Option(String code,int expenseCoins,int unitCostCoins,int lostBuyers,int addedBuyers,
                         int trustDelta,int supplyDelta,int rentDeltaCoins) {}
    public record StoryState(int trust,int supply,int rentSurchargeCoins,int echoMonths,Kind previous) {
        public StoryState {
            if (trust < -2 || trust > 2 || supply < -2 || supply > 2
                    || rentSurchargeCoins < 0 || rentSurchargeCoins > 30
                    || echoMonths < 0 || echoMonths > 2) throw new IllegalArgumentException("INVALID_V6_STORY_STATE");
        }
        public static StoryState initial() { return new StoryState(0,0,0,0,null); }
    }
    public record Resolution(Kind kind,Option selected,StoryState next,
                             MonthlyShopRules.TradingAdjustment immediate) {}
    public record Echo(StoryState next,MonthlyShopRules.TradingAdjustment adjustment) {}

    public Kind draw(int roomSeed,int month,StoryState state) {
        if (roomSeed < 0 || month < 1 || month > 12 || state == null)
            throw new IllegalArgumentException("INVALID_V6_CONFLICT_DRAW");
        Kind[] kinds=Kind.values();
        int mixed=roomSeed ^ Integer.rotateLeft(month * 0x9e3779b9,11)
                ^ Integer.rotateLeft(state.trust()*31 + state.supply()*17,7);
        mixed ^= mixed >>> 16; mixed *= 0x7feb352d; mixed ^= mixed >>> 15;
        int index=Math.floorMod(mixed,kinds.length);
        for (int i=0;i<kinds.length;i++) {
            Kind candidate=kinds[(index+i)%kinds.length];
            if (state.previous()==null || candidate.family()!=state.previous().family()) return candidate;
        }
        throw new IllegalStateException("NO_V6_CONFLICT");
    }

    public Resolution resolve(Kind kind,String optionCode,StoryState before) {
        if (kind==null || before==null || optionCode==null)
            throw new IllegalArgumentException("INVALID_V6_CONFLICT_CHOICE");
        Option selected=kind.options().stream().filter(option->option.code().equals(optionCode))
                .findFirst().orElseThrow(()->new IllegalArgumentException("INVALID_V6_CONFLICT_CHOICE"));
        StoryState next=new StoryState(clamp(before.trust()+selected.trustDelta()),
                clamp(before.supply()+selected.supplyDelta()),
                Math.max(0,Math.min(30,before.rentSurchargeCoins()+selected.rentDeltaCoins())),
                2,kind);
        MonthlyShopRules.TradingAdjustment immediate=adjustment(selected.expenseCoins()*100L,
                selected.unitCostCoins()*100L,selected.lostBuyers(),selected.addedBuyers(),
                kind.name(),selected.code());
        return new Resolution(kind,selected,next,immediate);
    }

    /** Apply once per following virtual month, then decrement the echo duration. */
    public Echo advanceEcho(StoryState before) {
        if (before==null) throw new IllegalArgumentException("INVALID_V6_STORY_STATE");
        int carry=before.echoMonths();
        int lost=carry>0 && before.trust()<0 ? -before.trust() : 0;
        int extra=carry>0 && before.trust()>0 ? before.trust() : 0;
        long unitCost=carry>0 && before.supply()<0 ? -before.supply()*100L : 0;
        StoryState next=new StoryState(before.trust(),before.supply(),before.rentSurchargeCoins(),
                Math.max(0,carry-1),before.previous());
        return new Echo(next,adjustment(before.rentSurchargeCoins()*100L,unitCost,lost,extra,
                carry>0?"V6_ECHO":null,null));
    }

    private static Option o(String code,int expense,int unitCost,int lost,int added,int trust,int supply,int rent) {
        return new Option(code,expense,unitCost,lost,added,trust,supply,rent);
    }
    private static int clamp(int value) { return Math.max(-2,Math.min(2,value)); }
    private static MonthlyShopRules.TradingAdjustment adjustment(long expense,long unitCost,
            int lost,int added,String event,String choice) {
        return new MonthlyShopRules.TradingAdjustment(expense,unitCost,
                Collections.nCopies(added,20),
                lost,event,choice==null?List.of():List.of(choice));
    }
}
