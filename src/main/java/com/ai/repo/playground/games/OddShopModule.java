package com.ai.repo.playground.games;

import com.ai.repo.playground.framework.GameKey;
import com.ai.repo.playground.framework.GameModule;
import com.ai.repo.playground.framework.GameRegistry;
import com.ai.repo.playground.rules.*;
import java.util.List;

/** Adapter for the existing ledger; versions preserve all established economics. */
public final class OddShopModule implements GameModule<MonthlyShopRules.State,
        OddShopModule.Configuration, OddShopModule.AdvanceMonth, MonthlyShopRules.Summary> {
    public record Configuration(int horizonMonths, MonthlyShopRules.Environment environment) {}
    public record AdvanceMonth(V5ShopRules.Strategy strategy, V5ShopRules.Signal signal,
            V5ShopRules.Response response, V5FranchiseOffer franchise, boolean variableDemand,
            MonthlyShopRules.TradingAdjustment external) {}

    private final GameKey key;
    private final MonthlyShopRules ledger = new MonthlyShopRules();
    private final V5ShopRules strategyRules = new V5ShopRules();

    private OddShopModule(String ruleVersion) { key = new GameKey("ODD_SHOP", ruleVersion); }
    public static GameRegistry registry() {
        return new GameRegistry(List.of("0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0", "1.1", "1.2", "1.3")
                .stream().map(OddShopModule::new).toList());
    }
    @Override public GameKey key() { return key; }
    @Override public int stateSchemaVersion() { return 1; }
    @Override public MonthlyShopRules.State initialize(Configuration configuration) {
        return ledger.initialize(configuration.horizonMonths(), configuration.environment());
    }
    @Override public MonthlyShopRules.State reduce(MonthlyShopRules.State state, AdvanceMonth command) {
        return advance(state, command.strategy(), command.signal(), command.response(), command.franchise(),
                command.variableDemand(), command.external()).game();
    }
    public V5ShopRules.MonthResult advance(MonthlyShopRules.State state, V5ShopRules.Strategy strategy,
            V5ShopRules.Signal signal, V5ShopRules.Response response, V5FranchiseOffer franchise,
            boolean variableDemand, MonthlyShopRules.TradingAdjustment external) {
        boolean expectedDemand = List.of("0.7", "0.8", "0.9", "1.0", "1.1", "1.2", "1.3").contains(key.ruleVersion());
        if (variableDemand != expectedDemand) throw new IllegalArgumentException("GAME_RULE_VERSION_MISMATCH");
        return strategyRules.advance(state, strategy, signal, response, franchise, variableDemand, external);
    }
    public V7StoryRules.Incident nextIncident(int seed, int month, V6ConflictRules.StoryState story,
            V7StoryRules.Execution prior, boolean scheduled) {
        if (List.of("1.1", "1.2", "1.3").contains(key.ruleVersion()))
            return new V7StoryRules().next(seed, month, story, prior, scheduled);
        return new V7StoryRules.Incident(scheduled ? new V6ConflictRules().draw(seed, month, story) : null, null);
    }
    public V7StoryRules.Execution executedPlan(MonthlyShopRules.State before, V5ShopRules.MonthResult result,
            V6MonthlyPlan plan, int signedProduction) {
        if (plan == null || result.game().ending() != MonthlyShopRules.Ending.RUNNING) return null;
        MonthlyShopRules.MonthlyReport report = result.game().reports().get(result.game().reports().size() - 1);
        int produced = result.game().inventory().stream().mapToInt(MonthlyShopRules.Batch::units).sum()
                + report.soldUnits() - before.inventory().stream().mapToInt(MonthlyShopRules.Batch::units).sum();
        return new V7StoryRules.Execution(report.month(), plan, produced > signedProduction,
                !result.budgetGuardTriggered() && result.marketingSpentMinor() >= 600,
                report.soldUnits() > 0);
    }
    @Override public MonthlyShopRules.Summary summarize(MonthlyShopRules.State state) {
        return ledger.summary(state);
    }
    public MonthlyShopRules.Summary summarize(MonthlyShopRules.State state, V6FoundingAgreement agreement) {
        return ledger.summary(state, agreement);
    }
}
