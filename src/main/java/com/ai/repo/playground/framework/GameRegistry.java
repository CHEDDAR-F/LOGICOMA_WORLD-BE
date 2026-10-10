package com.ai.repo.playground.framework;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Immutable version routing. Never silently upgrade a saved room to the latest rules. */
public final class GameRegistry {
    private final Map<GameKey, GameModule<?, ?, ?, ?>> modules;

    public GameRegistry(Collection<? extends GameModule<?, ?, ?, ?>> definitions) {
        Map<GameKey, GameModule<?, ?, ?, ?>> indexed = new HashMap<>();
        for (GameModule<?, ?, ?, ?> module : definitions) {
            if (indexed.putIfAbsent(module.key(), module) != null)
                throw new IllegalArgumentException("DUPLICATE_GAME_VERSION");
        }
        modules = Map.copyOf(indexed);
    }

    public <M extends GameModule<?, ?, ?, ?>> M require(GameKey key, Class<M> type) {
        GameModule<?, ?, ?, ?> module = modules.get(key);
        if (module == null) throw new IllegalArgumentException("UNSUPPORTED_GAME_VERSION");
        if (!type.isInstance(module)) throw new IllegalArgumentException("GAME_MODULE_TYPE_MISMATCH");
        return type.cast(module);
    }
}
