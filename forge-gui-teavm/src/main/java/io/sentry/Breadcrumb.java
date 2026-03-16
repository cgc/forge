package io.sentry;

/**
 * Stub replacement for {@code io.sentry.Breadcrumb}.
 *
 * <p>Referenced by Sentry SDK internals that are transitively reachable from
 * Forge code.  TeaVM's dependency analysis encounters references to this class
 * in the compiled Forge bytecode (e.g., {@code BugReporter}).  This empty stub
 * satisfies those references; the class is never instantiated on the web target
 * because the Sentry reporting code paths are guarded by the crash-reporting
 * opt-in preference which is always disabled on the web.
 */
public final class Breadcrumb {
}
