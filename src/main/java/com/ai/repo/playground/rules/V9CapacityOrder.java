package com.ai.repo.playground.rules;

import java.util.List;

/** Finite, persisted three-month commitment. Amounts are virtual game coins. */
public final class V9CapacityOrder {
    public enum Choice { SMALL, LARGE, DECLINE }
    public enum Status { ACTIVE, COMPLETED, BREACHED, CANCELLED }
    public record Terms(Choice choice,int fromMonth,int throughMonth,int monthlyUnits,
                        int unitPriceCoins,int completionBonusCoins,int missedMonthPenaltyCoins) {}
    public record Contract(Choice choice,int signedMonth,Status status,List<MonthlyShopRules.CapacityDelivery> deliveries) {
        public Contract {
            if (choice==null || choice==Choice.DECLINE || signedMonth!=3 || status==null || deliveries==null
                    || deliveries.size()>3) throw new IllegalArgumentException("INVALID_CAPACITY_ORDER");
            deliveries=List.copyOf(deliveries);
            for(int i=0;i<deliveries.size();i++)
                if(deliveries.get(i).month()!=i+4) throw new IllegalArgumentException("INVALID_ORDER_DELIVERY_SEQUENCE");
        }
    }
    public static List<Terms> offers() { return List.of(terms(Choice.SMALL),terms(Choice.LARGE)); }
    public static Terms terms(Choice choice) {
        if(choice==null || choice==Choice.DECLINE) throw new IllegalArgumentException("ORDER_NOT_SIGNED");
        return choice==Choice.SMALL?new Terms(choice,4,6,2,18,8,8):new Terms(choice,4,6,4,17,15,14);
    }
    public static Contract sign(Choice choice) { return new Contract(choice,3,Status.ACTIVE,List.of()); }
    public static MonthlyShopRules.CapacityReservation reservation(Contract contract,int month) {
        if(contract==null || contract.status()!=Status.ACTIVE || month<4 || month>6) return null;
        if(contract.deliveries().size()!=month-4) throw new IllegalArgumentException("ORDER_MONTH_GAP");
        Terms terms=terms(contract.choice());
        boolean bonus=month==6 && contract.deliveries().stream().allMatch(MonthlyShopRules.CapacityDelivery::fulfilled);
        return new MonthlyShopRules.CapacityReservation(terms.monthlyUnits(),terms.unitPriceCoins(),
                terms.missedMonthPenaltyCoins()*100L,bonus?terms.completionBonusCoins()*100L:0);
    }
    public static Contract record(Contract contract,MonthlyShopRules.CapacityDelivery delivery) {
        if(contract==null || contract.status()!=Status.ACTIVE || delivery==null)
            throw new IllegalArgumentException("INVALID_ORDER_DELIVERY");
        if(delivery.month()!=4+contract.deliveries().size()) throw new IllegalArgumentException("ORDER_MONTH_GAP");
        var all=new java.util.ArrayList<>(contract.deliveries());all.add(delivery);
        Status status=all.size()==3?(all.stream().allMatch(MonthlyShopRules.CapacityDelivery::fulfilled)
                ?Status.COMPLETED:Status.BREACHED):Status.ACTIVE;
        return new Contract(contract.choice(),3,status,all);
    }
    /** Missed promises affect customer trust for the following two months, never partner trust. */
    public static V6ConflictRules.StoryState afterDelivery(V6ConflictRules.StoryState story,
            MonthlyShopRules.CapacityDelivery delivery) {
        if(delivery.fulfilled()) return story;
        return new V6ConflictRules.StoryState(Math.max(-2,story.trust()-1),story.supply(),
                story.rentSurchargeCoins(),Math.max(2,story.echoMonths()),story.previous());
    }
    public static Contract cancel(Contract contract) {
        return contract==null || contract.status()!=Status.ACTIVE?contract:
                new Contract(contract.choice(),3,Status.CANCELLED,contract.deliveries());
    }
}
