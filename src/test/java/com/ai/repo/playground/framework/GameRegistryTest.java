package com.ai.repo.playground.framework;

import com.ai.repo.playground.games.OddShopModule;
import com.ai.repo.playground.rules.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameRegistryTest {
    // A three-stage exploration fixture uses no shop state, currency or shop actions.
    record Expedition(int stages, int energy, Map<Integer, Route> positions) {
        Expedition { positions = Map.copyOf(positions); }
    }
    enum Route { SAFE, FAST }
    record Position(int actor, Route route) {}
    static final class Exploration implements GameModule<Expedition, Integer, Position, Boolean> {
        public GameKey key() { return new GameKey("EXPLORATION", "0.1"); }
        public int stateSchemaVersion() { return 1; }
        public Expedition initialize(Integer energy) { return new Expedition(0, energy, Map.of()); }
        public Expedition reduce(Expedition state, Position command) {
            if (state.stages() >= 3 || state.energy() <= 0) throw new IllegalArgumentException("FINISHED");
            if (command.actor() < 1 || command.actor() > 2 || command.route() == null
                    || state.positions().containsKey(command.actor())) throw new IllegalArgumentException("INVALID_POSITION");
            Map<Integer, Route> positions = new HashMap<>(state.positions());
            positions.put(command.actor(), command.route());
            if (positions.size() < 2) return new Expedition(state.stages(), state.energy(), positions);
            // Different independent choices resolve to the safe route, without claiming consensus.
            int cost = positions.values().stream().allMatch(route -> route == Route.FAST) ? 2 : 1;
            return new Expedition(state.stages() + 1, state.energy() - cost, Map.of());
        }
        public Boolean summarize(Expedition state) { return state.stages() == 3 && state.energy() >= 0; }
    }
    @Test void distinctGameUsesSameRegistryAndPureLifecycle() {
        Exploration exploration = new Exploration();
        GameRegistry registry = new GameRegistry(List.of(exploration,
                OddShopModule.registry().require(new GameKey("ODD_SHOP", "1.0"), OddShopModule.class)));
        Exploration game = registry.require(exploration.key(), Exploration.class);
        Expedition initial = game.initialize(4);
        Expedition pending = game.reduce(initial, new Position(1, Route.SAFE));
        assertEquals(0, pending.stages());
        assertThrows(IllegalArgumentException.class, () -> game.reduce(pending, new Position(1, Route.FAST)));
        Expedition first = game.reduce(pending, new Position(2, Route.SAFE));
        Expedition second = game.reduce(game.reduce(first, new Position(2, Route.FAST)), new Position(1, Route.FAST));
        Expedition state = game.reduce(game.reduce(second, new Position(1, Route.FAST)), new Position(2, Route.SAFE));
        assertTrue(game.summarize(state));
        assertEquals(new Expedition(0, 4, Map.of()), initial);
        assertThrows(IllegalArgumentException.class, () -> game.reduce(state, new Position(1, Route.SAFE)));
        assertThrows(IllegalArgumentException.class, () -> registry.require(game.key(), OddShopModule.class));
        assertThrows(IllegalArgumentException.class, () -> registry.require(new GameKey("EXPLORATION", "0.2"), Exploration.class));
        assertThrows(IllegalArgumentException.class, () -> new GameRegistry(List.of(game, game)));
    }
    @Test void budgetGuardAndUnexecutedProductionNeverBecomeIncidentSources() {
        var game = OddShopModule.registry().require(new GameKey("ODD_SHOP", "1.1"), OddShopModule.class);
        var initial = game.initialize(new OddShopModule.Configuration(12,
                new MonthlyShopRules.Environment(MonthlyShopRules.Shock.NONE, 1, 12)));
        var strategy = new V5ShopRules.Strategy(5, 16, MonthlyShopRules.CAPITAL,
                V5ShopRules.Audience.NEIGHBORS, V5ShopRules.Channel.LOCAL_EVENT,
                V5ShopRules.ServicePromise.FAST, 600);
        var result = game.advance(initial, strategy, V5ShopRules.Signal.NORMAL,
                V5ShopRules.Response.KEEP_IDENTITY, null, true, MonthlyShopRules.TradingAdjustment.NONE);
        assertTrue(result.budgetGuardTriggered());
        var proposed = new V6MonthlyPlan(V6MonthlyPlan.ProductionBand.EXPAND,
                V6MonthlyPlan.MarketingAction.COMMUNITY_EVENT, V6MonthlyPlan.ServiceFocus.SPEED, null);
        var executed = game.executedPlan(initial, result, proposed, 4);
        assertNotNull(executed);
        assertFalse(executed.expandedProduction());
        assertFalse(executed.communityMarketing());
        assertFalse(executed.servedCustomers());
        assertNull(game.nextIncident(1, 2, new V6ConflictRules.StoryState(-1, -1, 0, 1, null), executed, false).kind());
        assertNull(game.executedPlan(initial, result, null, 4));
    }
    @Test void savedVersionControlsWhetherPriorChoicesCauseIncidents() {
        var registry = OddShopModule.registry();
        var prior = new V6MonthlyPlan(V6MonthlyPlan.ProductionBand.STANDARD,
                V6MonthlyPlan.MarketingAction.COMMUNITY_EVENT, V6MonthlyPlan.ServiceFocus.RELATIONSHIP, null);
        var old = registry.require(new GameKey("ODD_SHOP", "1.0"), OddShopModule.class);
        var current = registry.require(new GameKey("ODD_SHOP", "1.1"), OddShopModule.class);
        assertNull(old.nextIncident(1, 3, V6ConflictRules.StoryState.initial(), new V7StoryRules.Execution(2, prior, false, true, true), false).kind());
        assertEquals(V6ConflictRules.Kind.RUSH_BULK_ORDER,
                current.nextIncident(1, 3, V6ConflictRules.StoryState.initial(), new V7StoryRules.Execution(2, prior, false, true, true), false).kind());
    }
    @Test void registeredShopVersionsReplayExistingEconomicsExactly() {
        GameRegistry registry = OddShopModule.registry();
        V5ShopRules.Strategy strategy = new V5ShopRules.Strategy(4, 16, 2000,
                V5ShopRules.Audience.NEIGHBORS, V5ShopRules.Channel.NONE,
                V5ShopRules.ServicePromise.COMMUNITY, 0);
        for (String version : List.of("0.6", "0.7", "0.8", "0.9", "1.0", "1.1")) {
            OddShopModule game = registry.require(new GameKey("ODD_SHOP", version), OddShopModule.class);
            MonthlyShopRules.Environment environment = new MonthlyShopRules.Environment(MonthlyShopRules.Shock.NONE, 1, 12);
            MonthlyShopRules.State expected = new MonthlyShopRules().initialize(12, environment);
            MonthlyShopRules.State actual = game.initialize(new OddShopModule.Configuration(12, environment));
            for (int month = 0; month < 12; month++) {
                var command = new OddShopModule.AdvanceMonth(strategy, V5ShopRules.Signal.NORMAL,
                        V5ShopRules.Response.KEEP_IDENTITY, null, !version.equals("0.6"),
                        MonthlyShopRules.TradingAdjustment.NONE);
                expected = new V5ShopRules().advance(expected, strategy, command.signal(), command.response(),
                        null, command.variableDemand(), command.external()).game();
                actual = game.reduce(actual, command);
                assertEquals(expected, actual, version + ": month " + month);
            }
            assertEquals(new MonthlyShopRules().summary(expected), game.summarize(actual));
        }
    }
}
