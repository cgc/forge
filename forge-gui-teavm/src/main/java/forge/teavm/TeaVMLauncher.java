package forge.teavm;

import com.badlogic.gdx.ApplicationListener;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplication;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplicationConfiguration;

import forge.Forge;

/**
 * Browser-side entry point for the Forge TeaVM web build.
 *
 * <p>This class is the TeaVM equivalent of {@code forge.app.GameLauncher} (the
 * LWJGL3 desktop launcher) and {@code forge.html.GwtLauncher} (the GWT
 * launcher).  Its {@link #main} method is transpiled to JavaScript by the
 * {@link BuildForgeTeaVM} build-time driver and runs inside the browser when
 * the game page loads.
 *
 * <p>Unlike the GWT approach, this class does <em>not</em> extend a
 * framework-specific base class.  It is a plain Java class with a {@code main}
 * method, which the {@code WebApplication} constructor immediately invokes on
 * the browser's main loop via {@code requestAnimationFrame}.
 *
 * <h2>Viability notes</h2>
 * <ul>
 *   <li><b>Audio:</b> gdx-teavm uses Howler.js for audio playback.  Only
 *       {@code .mp3} and {@code .ogg} are supported; {@code .midi} files used
 *       for background music must be converted to {@code .ogg} or omitted on
 *       the web target.</li>
 *   <li><b>Threading:</b> The JavaScript target is single-threaded.
 *       {@code Thread.sleep} and most {@code java.util.concurrent} patterns
 *       will fail at runtime.  Long-running operations must be broken into
 *       callbacks or deferred via {@code Gdx.app.postRunnable}.</li>
 *   <li><b>Box2D:</b> The Adventure mode uses gdx-box2d.  gdx-teavm does not
 *       currently ship a pre-compiled Box2D Wasm module, so Adventure mode
 *       will not function on the web target until this is resolved.</li>
 *   <li><b>Asset size:</b> The full Forge asset set (card images, etc.) is
 *       several gigabytes.  A lazy-loading / CDN strategy will be required for
 *       a practical web deployment; only the base skin and core data files
 *       should be bundled at startup.</li>
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
         * Mirrors the call in GameLauncher (LWJGL3 desktop) and GwtLauncher:
         *   Forge.getApp(hwInfo, clipboard, deviceAdapter, assetsDir, ...)
         *
         * For the TeaVM / web target:
         *   hwInfo        : null – no hardware-level info in a browser
         *   clipboard     : WebApplication.get().getClipboard() is used
         *                   internally; passing null here is intentional because
         *                   the WebApplication constructor sets up Gdx.app before
         *                   Forge.getApp runs.  TODO: confirm whether a
         *                   pre-built WebClipboard should be passed instead.
         *   deviceAdapter : TeaVMAdapter – browser-safe IDeviceAdapter
         *   assetsDir     : "" – assets are served relative to the page URL
         *   portrait      : false – default to landscape; responsive
         */
        ApplicationListener appListener = Forge.getApp(
                /* hwInfo         */ null,
                /* clipboard      */ null,   // TODO: new WebClipboard() once Gdx.app is available
                /* deviceAdapter  */ new TeaVMAdapter(),
                /* assetsDir      */ "",
                /* propertyConfig */ false,
                /* portrait       */ false,
                /* totalRAM       */ 0,
                /* isTablet       */ true,
                /* androidAPI     */ 0);

        /*
         * WebApplication is the gdx-teavm equivalent of Lwjgl3Application.
         * Its constructor sets up Gdx.app, Gdx.graphics, Gdx.input, etc.,
         * then starts the requestAnimationFrame game loop.
         */
        new WebApplication(appListener, config);
    }
}
