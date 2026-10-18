package com.ai.repo.playground.rules;

/** Rule 1.1: bounded incidents caused by an actually executed prior-month choice. */
public final class V7StoryRules {
    public record Cause(int sourceMonth, String sourceField, String sourceValue) {}
    public record Incident(V6ConflictRules.Kind kind, Cause cause) {}
    public record Execution(int month, V6MonthlyPlan plan, boolean expandedProduction,
                            boolean communityMarketing, boolean servedCustomers) {}
    private final V6ConflictRules incidents = new V6ConflictRules();

    public Incident next(int seed, int month, V6ConflictRules.StoryState story,
                         Execution prior, boolean scheduled) {
        if (prior != null && prior.plan() != null && prior.month() == month - 1) {
            V6MonthlyPlan priorPlan = prior.plan();
            int priorMonth = prior.month();
            V6ConflictRules.Kind kind = null;
            String field = null, value = null;
            if (priorPlan.productionBand() == V6MonthlyPlan.ProductionBand.EXPAND && prior.expandedProduction() && story.supply() < 0) {
                kind = V6ConflictRules.Kind.SUPPLIER_SHORTAGE;
                field = "productionBand"; value = "EXPAND";
            } else if (priorPlan.marketingAction() == V6MonthlyPlan.MarketingAction.COMMUNITY_EVENT && prior.communityMarketing()) {
                kind = V6ConflictRules.Kind.RUSH_BULK_ORDER;
                field = "marketingAction"; value = "COMMUNITY_EVENT";
            } else if (priorPlan.serviceFocus() == V6MonthlyPlan.ServiceFocus.SPEED && prior.servedCustomers() && story.trust() < 0) {
                kind = V6ConflictRules.Kind.QUALITY_DOUBT;
                field = "serviceFocus"; value = "SPEED";
            }
            if (kind != null && (story.previous() == null || story.previous().family() != kind.family()))
                return new Incident(kind, new Cause(priorMonth, field, value));
        }
        return new Incident(scheduled ? incidents.draw(seed, month, story) : null, null);
    }
}
