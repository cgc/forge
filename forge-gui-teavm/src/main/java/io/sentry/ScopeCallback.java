package io.sentry;

/**
 * Stub replacement for {@code io.sentry.ScopeCallback}.
 *
 * <p>This is the functional interface used as the second argument to
 * {@code Sentry.configureScope(ScopeType, ScopeCallback)} in the Sentry 8.x
 * SDK.  Forge compiles its lambda against this interface signature.
 */
@FunctionalInterface
public interface ScopeCallback {
    void run(IScope scope);
}
