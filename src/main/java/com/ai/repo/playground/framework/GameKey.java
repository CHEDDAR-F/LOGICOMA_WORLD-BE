package com.ai.repo.playground.framework;

/** Rules are pinned independently of the transport and persisted-state schema. */
public record GameKey(String gameId, String ruleVersion) {
    public GameKey {
        if (gameId == null || gameId.isBlank() || ruleVersion == null || ruleVersion.isBlank())
            throw new IllegalArgumentException("INVALID_GAME_KEY");
    }
}
