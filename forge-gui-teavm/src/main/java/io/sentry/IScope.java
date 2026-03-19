package io.sentry;

/**
 * Stub replacement for {@code io.sentry.IScope}.
 *
 * <p>Used in the lambda body passed to {@code Sentry.configureScope} in
 * {@code forge/Forge.java}:
 * <pre>
 *   Sentry.configureScope(ScopeType.GLOBAL, scope -&gt; {
 *       scope.getContexts().setDevice(hwInfo.device());
 *       scope.getContexts().setOperatingSystem(hwInfo.os());
 *   });
 * </pre>
 */
public interface IScope {

    /**
     * Returns the mutable context map that holds device / OS metadata.
     * In the stub, this returns a no-op {@link Contexts} instance.
     */
    Contexts getContexts();
}
