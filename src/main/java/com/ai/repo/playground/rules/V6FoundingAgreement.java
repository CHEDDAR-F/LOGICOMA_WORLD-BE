package com.ai.repo.playground.rules;

import java.util.Set;

/** Signed virtual funding and work split. No real-world payment or debt is created. */
public record V6FoundingAgreement(int hostCapitalCoins, int guestCapitalCoins,
                                  int hostProfitPercent, Seat serviceLead,
                                  Seat supplyLead, Seat communityLead) {
    public static final int TOTAL_CAPITAL_COINS = 200;
    private static final Set<Integer> CAPITAL_CHOICES = Set.of(0, 50, 80, 100, 120, 150, 200);
    private static final Set<Integer> PROFIT_CHOICES = Set.of(30, 40, 50, 60, 70);

    public enum Seat { HOST, GUEST }

    public V6FoundingAgreement {
        require(CAPITAL_CHOICES.contains(hostCapitalCoins)
                && CAPITAL_CHOICES.contains(guestCapitalCoins)
                && hostCapitalCoins + guestCapitalCoins == TOTAL_CAPITAL_COINS,
                "INVALID_FOUNDING_CAPITAL");
        require(PROFIT_CHOICES.contains(hostProfitPercent), "INVALID_FOUNDING_PROFIT_SPLIT");
        require(serviceLead != null && supplyLead != null && communityLead != null
                && (serviceLead == Seat.HOST || supplyLead == Seat.HOST || communityLead == Seat.HOST)
                && (serviceLead == Seat.GUEST || supplyLead == Seat.GUEST || communityLead == Seat.GUEST),
                "INVALID_FOUNDING_DUTIES");
    }

    public int guestProfitPercent() { return 100 - hostProfitPercent; }

    public Allocation allocate(long returnedCapitalMinor) {
        require(returnedCapitalMinor >= 0, "INVALID_FOUNDING_RETURN");
        long host;
        if (returnedCapitalMinor < TOTAL_CAPITAL_COINS * 100L) {
            // Losses follow actual virtual capital at risk. The remainder goes to the guest.
            host = Math.multiplyExact(returnedCapitalMinor, hostCapitalCoins) / TOTAL_CAPITAL_COINS;
        } else {
            long profit = returnedCapitalMinor - TOTAL_CAPITAL_COINS * 100L;
            host = hostCapitalCoins * 100L
                    + Math.multiplyExact(profit, hostProfitPercent) / 100L;
        }
        return new Allocation(host, returnedCapitalMinor - host,
                returnedCapitalMinor - TOTAL_CAPITAL_COINS * 100L);
    }

    public record Allocation(long hostReturnedMinor, long guestReturnedMinor, long netProfitMinor) {}

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
