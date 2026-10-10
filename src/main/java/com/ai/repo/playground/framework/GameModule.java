package com.ai.repo.playground.framework;

/** Pure rules: no database, model calls, credentials, clock or mutable room state.
 * The room runtime authorizes a command before invoking a module and persists its result.
 */
public interface GameModule<S, I, C, R> {
    GameKey key();
    int stateSchemaVersion();
    S initialize(I configuration);
    S reduce(S state, C command);
    R summarize(S state);
}
