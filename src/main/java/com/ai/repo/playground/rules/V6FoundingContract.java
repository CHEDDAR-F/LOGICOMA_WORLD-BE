package com.ai.repo.playground.rules;

import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;

/** Strict wire format for terms signed with the shop proposal. */
public final class V6FoundingContract {
    private static final Set<String> FIELDS = Set.of("hostCapitalCoins", "guestCapitalCoins",
            "hostProfitPercent", "serviceLead", "supplyLead", "communityLead");

    private V6FoundingContract() {}

    public static V6FoundingAgreement parse(JsonNode node) {
        require(node != null && node.isObject() && node.size() == FIELDS.size());
        node.fieldNames().forEachRemaining(name -> require(FIELDS.contains(name)));
        try {
            return new V6FoundingAgreement(number(node, "hostCapitalCoins"),
                    number(node, "guestCapitalCoins"), number(node, "hostProfitPercent"),
                    seat(node, "serviceLead"), seat(node, "supplyLead"), seat(node, "communityLead"));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("INVALID_FOUNDING_AGREEMENT", error);
        }
    }

    private static int number(JsonNode node, String key) {
        JsonNode value = node.get(key);
        require(value != null && value.isIntegralNumber() && value.canConvertToInt());
        return value.intValue();
    }

    private static V6FoundingAgreement.Seat seat(JsonNode node, String key) {
        JsonNode value = node.get(key);
        require(value != null && value.isTextual());
        return V6FoundingAgreement.Seat.valueOf(value.textValue());
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("INVALID_FOUNDING_AGREEMENT");
    }
}
