package io.sentry;

/**
 * Stub replacement for {@code io.sentry.ScopeType}.
 *
 * <p>Forge uses {@code ScopeType.GLOBAL} in the {@code Sentry.configureScope}
 * call in {@code forge/Forge.java}.  This no-op enum satisfies that reference.
 */
public enum ScopeType {
    GLOBAL,
    ISOLATION,
    CURRENT
}
