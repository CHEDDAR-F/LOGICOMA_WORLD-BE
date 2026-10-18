package com.ai.repo.playground.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V6FoundingContractTest {
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode parse(String source) {
        try { return json.readTree(source); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    @Test void signedUnequalFundingAndLaborTermsRoundTrip() {
        JsonNode terms = parse("""
                {"hostCapitalCoins":150,"guestCapitalCoins":50,"hostProfitPercent":60,
                 "serviceLead":"GUEST","supplyLead":"HOST","communityLead":"GUEST"}
                """);
        V6FoundingAgreement agreement = V6FoundingContract.parse(terms);
        assertEquals(150, agreement.hostCapitalCoins());
        assertEquals(40, agreement.guestProfitPercent());
        assertEquals(agreement, V6FoundingContract.parse(parse(terms.toString())));
    }

    @Test void rejectsHiddenTermsAndUnboundedOrIncompleteAgreement() {
        for (String terms : new String[]{
                "{}",
                "{\"hostCapitalCoins\":150,\"guestCapitalCoins\":50,\"hostProfitPercent\":60,\"serviceLead\":\"GUEST\",\"supplyLead\":\"HOST\",\"communityLead\":\"GUEST\",\"sidePayment\":999}",
                "{\"hostCapitalCoins\":150,\"guestCapitalCoins\":50,\"hostProfitPercent\":\"60\",\"serviceLead\":\"GUEST\",\"supplyLead\":\"HOST\",\"communityLead\":\"GUEST\"}",
                "{\"hostCapitalCoins\":150,\"guestCapitalCoins\":50,\"hostProfitPercent\":60,\"serviceLead\":\"HOST\",\"supplyLead\":\"HOST\",\"communityLead\":\"HOST\"}"
        }) assertThrows(IllegalArgumentException.class, () -> V6FoundingContract.parse(parse(terms)));
    }
}
