package com.ai.repo.playground.rules;

import org.junit.jupiter.api.Test;

import static com.ai.repo.playground.rules.V6FoundingAgreement.Seat.GUEST;
import static com.ai.repo.playground.rules.V6FoundingAgreement.Seat.HOST;
import static org.junit.jupiter.api.Assertions.*;

class V6FoundingAgreementTest {
    private final V6FoundingAgreement uneven = new V6FoundingAgreement(150, 50, 60,
            HOST, GUEST, HOST);

    @Test void profitReturnsCapitalBeforeApplyingSignedProfitShare() {
        var payout = uneven.allocate(26_000);
        assertEquals(18_600, payout.hostReturnedMinor());
        assertEquals(7_400, payout.guestReturnedMinor());
        assertEquals(6_000, payout.netProfitMinor());
        assertEquals(26_000, payout.hostReturnedMinor() + payout.guestReturnedMinor());
    }

    @Test void lossFallsOnContributedCapitalWithoutCreatingDebt() {
        var payout = uneven.allocate(14_400);
        assertEquals(10_800, payout.hostReturnedMinor());
        assertEquals(3_600, payout.guestReturnedMinor());
        assertEquals(-5_600, payout.netProfitMinor());
        assertEquals(14_400, payout.hostReturnedMinor() + payout.guestReturnedMinor());
    }

    @Test void laborPartnerCanShareProfitWithoutBearingUnfundedLoss() {
        var agreement = new V6FoundingAgreement(200, 0, 70, HOST, GUEST, GUEST);
        assertEquals(0, agreement.allocate(8_000).guestReturnedMinor());
        assertEquals(3_000, agreement.allocate(30_000).guestReturnedMinor());
    }

    @Test void rejectsInvalidMoneyAndSingleAgentControlOfAllDuties() {
        assertThrows(IllegalArgumentException.class,
                () -> new V6FoundingAgreement(150, 100, 60, HOST, GUEST, HOST));
        assertThrows(IllegalArgumentException.class,
                () -> new V6FoundingAgreement(100, 100, 90, HOST, GUEST, HOST));
        assertThrows(IllegalArgumentException.class,
                () -> new V6FoundingAgreement(100, 100, 50, HOST, HOST, HOST));
        assertThrows(IllegalArgumentException.class, () -> uneven.allocate(-1));
    }
}
