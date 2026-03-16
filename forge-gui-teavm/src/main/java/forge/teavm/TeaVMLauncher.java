package forge.teavm;

import com.badlogic.gdx.ApplicationListener;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplication;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplicationConfiguration;
import forge.Forge;
import org.teavm.jso.JSBody;

/**
 * Browser-side entry point for the Forge TeaVM web build.
 *
 * <p>This class is the TeaVM equivalent of {@code forge.app.GameLauncher} (the
 * LWJGL3 desktop launcher).  Its {@link #main} method is transpiled to
 * JavaScript by the {@link BuildForgeTeaVM} build-time driver and runs inside
 * the browser when the game page loads.
 *
 * <p>Unlike the GWT approach, this class does <em>not</em> extend a
 * framework-specific base class.  It is a plain Java class with a {@code main}
 * method, which the {@code WebApplication} constructor immediately invokes on
 * the browser's main loop via {@code requestAnimationFrame}.
 *
 * <h2>Readiness signal for automated testing</h2>
 * <p>Once {@code Forge.afterDBloaded} becomes {@code true} (the full card
 * database has loaded and the home screen is ready), the launcher sets
 * {@code window.__forgeReady = true} via a {@code @JSBody} call.  The
 * {@link HomeScreenLoadTest} polls this flag to verify a successful startup
 * without requiring manual inspection.
 *
 * <h2>Viability notes</h2>
 * <ul>
 *   <li><b>Threading:</b> The JavaScript target is single-threaded.
 *       {@code Thread.sleep} in game logic may silently become a no-op or
 *       throw; long-running operations should be broken into
 *       {@code Gdx.app.postRunnable} callbacks.</li>
 *   <li><b>Box2D:</b> Adventure mode uses gdx-box2d.  gdx-teavm does not
 *       currently ship a pre-compiled Box2D Wasm module, so Adventure mode
 *       will not function on the web target until this is resolved.</li>
 *   <li><b>Asset size:</b> The full Forge asset set is several gigabytes.
 *       A lazy-loading / CDN strategy is required; only the base skin and
 *       core data files should be bundled at startup.</li>
 *   <li><b>java.nio.file:</b> {@code Forge.java} and {@code FSkinFont.java}
 *       use {@code java.nio.file.Files} / {@code Paths} for path checks and
 *       translation file loading.  TeaVM's JS-mode emulation of these classes
 *       is limited; {@code Files.exists} returns {@code false} (safe
 *       fallback), but {@code Files.newInputStream} will throw.  Font
 *       translation fallback logic may need a web-specific path.</li>
 * </ul>
 */
public class TeaVMLauncher {

    public static void main(String[] args) {
        WebApplicationConfiguration config = new WebApplicationConfiguration();

        /*
         * Use the full browser window rather than a fixed pixel size.
         * width = 0 / height = 0 means "fill available space"; the canvas
         * resizes automatically when the browser window is resized.
         */
        config.width  = 0;
        config.height = 0;

        /*
         * Show asset download progress in the browser console during
         * development.  Set to false for release builds to reduce console noise.
         */
        config.showDownloadLogs = true;

        /*
         * Storage prefix used for browser localStorage keys.  Using a unique
         * prefix prevents data collisions if multiple web apps are hosted on
         * the same domain (e.g., different Forge versions on itch.io).
         */
        config.storagePrefix = "forge";

        /*
         * Mirrors the call in GameLauncher (LWJGL3 desktop):
         *   Forge.getApp(hwInfo, clipboard, deviceAdapter, assetsDir, ...)
         *
         * For the TeaVM / web target:
         *   hwInfo        : null – no hardware-level info in a browser;
         *                   this causes Forge.java's Sentry.configureScope call
         *                   to be skipped (guarded by hwInfo != null).
         *   clipboard     : null – WebApplication sets up Gdx.app.getClipboard()
         *                   internally; Forge uses that via Gdx.app.
         *   deviceAdapter : TeaVMAdapter – browser-safe IDeviceAdapter stub
         *   assetsDir     : "" – assets are served relative to the page URL
         *   portrait      : false – default to landscape
         */
        ApplicationListener appListener = Forge.getApp(
                /* hwInfo         */ null,
                /* clipboard      */ null,
                /* deviceAdapter  */ new TeaVMAdapter(),
                /* assetsDir      */ "",
                /* propertyConfig */ false,
                /* portrait       */ false,
                /* totalRAM       */ 0,
                /* isTablet       */ true,
                /* androidAPI     */ 0);

        /*
         * Wrap the real ApplicationListener in a thin delegate that sets
         * readiness signals used by HomeScreenLoadTest:
         *
         *   window.__forgeStarted – set in create(), before any asset loading.
         *     Verifies that the compiled JavaScript loaded correctly and that
         *     the LibGDX WebApplication lifecycle started.  Suitable as a fast
         *     CI smoke test that does not require the full card database.
         *
         *   window.__forgeReady – set in render() once Forge.afterDBloaded is
         *     true (the full card DB has loaded and the home screen is visible).
         *     Requires the complete Forge asset set and a longer timeout.
         */
        final ApplicationListener wrapped = appListener;
        ApplicationListener readySignalListener = new ApplicationListener() {
            private boolean readySignalled = false;

            @Override
            public void create() {
                wrapped.create();
                setForgeStartedFlag();
            }

            @Override public void resize(int w, int h) { wrapped.resize(w, h); }
            @Override public void pause()   { wrapped.pause(); }
            @Override public void resume()  { wrapped.resume(); }
            @Override public void dispose() { wrapped.dispose(); }

            @Override
            public void render() {
                wrapped.render();
                if (!readySignalled && Forge.afterDBloaded) {
                    setForgeReadyFlag();
                    readySignalled = true;
                }
            }
        };

        /*
         * WebApplication is the gdx-teavm equivalent of Lwjgl3Application.
         * Its constructor sets up Gdx.app, Gdx.graphics, Gdx.input, etc.,
         * then starts the requestAnimationFrame game loop.
         */
        new WebApplication(readySignalListener, config);
    }

    /**
     * Sets {@code window.__forgeStarted = true} once the LibGDX
     * {@code ApplicationListener.create()} method has been called.
     *
     * <p>This is the first readiness signal.  It fires before any Forge assets
     * are loaded and confirms that the compiled JavaScript executed without
     * fatal errors up to the start of the game lifecycle.  Used by
     * {@link HomeScreenLoadTest} as a fast smoke-test check.
     */
    @JSBody(script = "window.__forgeStarted = true;")
    private static native void setForgeStartedFlag();

    /**
     * Sets {@code window.__forgeReady = true} once the full card database has
     * loaded and the home screen is visible ({@code Forge.afterDBloaded}).
     *
     * <p>This is the second (optional) readiness signal.  It requires the
     * complete Forge asset set and typically takes 30-90 s.  Used by
     * {@link HomeScreenLoadTest} to verify end-to-end startup success when a
     * full asset set is available.
     */
    @JSBody(script = "window.__forgeReady = true;")
    private static native void setForgeReadyFlag();
}
