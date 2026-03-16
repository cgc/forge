package io.sentry;

import java.util.function.Consumer;

/**
 * Stub replacement for {@code io.sentry.Sentry}.
 *
 * <p>The real Sentry SDK is excluded from the {@code forge-gui-teavm}
 * compile and TeaVM-compilation classpaths (see {@code pom.xml}).  Forge
 * calls into Sentry for error reporting from several classes in
 * {@code forge-gui} and {@code forge-gui-mobile}.  This no-op stub satisfies
 * all those call sites so the app can be compiled by TeaVM and run in the
 * browser.
 *
 * <p>Error reporting on the web target can be implemented separately using
 * browser-native crash reporting (e.g., writing to {@code console.error} or
 * forwarding to a CORS-enabled Sentry endpoint via the browser SDK).
 *
 * <h2>API surface required by the forge codebase</h2>
 * <ul>
 *   <li>{@code Sentry.addBreadcrumb(String)} – forge-gui / BugReporter</li>
 *   <li>{@code Sentry.captureException(Throwable)} – BugReporter, SaveFileData,
 *       VCardDisplayArea</li>
 *   <li>{@code Sentry.captureException(Throwable, Hint)} – SaveFileData</li>
 *   <li>{@code Sentry.captureMessage(String)} – BugReporter,
 *       LibGDXImageFetcher, VCardDisplayArea</li>
 *   <li>{@code Sentry.configureScope(ScopeType, ScopeCallback)} – Forge.java</li>
 * </ul>
 */
public final class Sentry {

    private Sentry() { }

    public static void addBreadcrumb(String message) { }

    public static void captureException(Throwable throwable) { }

    /** Overload that accepts a {@link Hint} attachment (Sentry 8.x). */
    public static void captureException(Throwable throwable, Hint hint) { }

    public static void captureMessage(String message) { }

    /**
     * Sentry 8.x scope configuration callback.
     *
     * <p>In the real Sentry SDK, this applies scope mutations (e.g., attaching
     * device info) when {@code hwInfo != null}.  In the web build, {@code hwInfo}
     * is always {@code null} (see {@link forge.teavm.TeaVMLauncher}), so this
     * method body is effectively dead code.  The stub still needs to exist so
     * that TeaVM can compile the Forge class that calls it.
     */
    public static void configureScope(ScopeType scopeType, ScopeCallback callback) { }

    /**
     * Convenience overload accepting a plain {@link Consumer} so that call
     * sites compiled against older Sentry API versions also resolve.
     */
    public static void configureScope(ScopeType scopeType, Consumer<IScope> callback) { }
}
