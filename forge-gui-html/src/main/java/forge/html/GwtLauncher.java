package forge.html;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.gwt.GwtApplication;
import com.badlogic.gdx.backends.gwt.GwtApplicationConfiguration;
import com.badlogic.gdx.backends.gwt.GwtClipboard;

import forge.Forge;

/**
 * HTML5 / GWT entry point for Forge.
 *
 * <p>This class is the GWT equivalent of {@code forge.app.GameLauncher} (the
 * LWJGL3 desktop launcher found in {@code forge-gui-mobile-dev}).  It extends
 * {@link GwtApplication}, which is instantiated by the GWT host page
 * ({@code index.html}) via a generated bootstrap script produced by the GWT
 * compiler.
 *
 * <p><b>Viability notes:</b>
 * <ul>
 *   <li>The GWT compilation of {@code forge-gui-mobile} and its transitive
 *       dependencies has not yet been validated.  Many classes use Java APIs
 *       that are not available in GWT (see pom.xml for a full list).</li>
 *   <li>Asset loading must be reworked to use asynchronous HTTP requests
 *       instead of blocking file-system reads.</li>
 *   <li>Thread-based code paths in the game logic will need to be replaced
 *       with GWT-compatible asynchronous patterns (e.g. timers, callbacks)
 *       because JavaScript is single-threaded.</li>
 * </ul>
 */
public class GwtLauncher extends GwtApplication {

    /** Default canvas size; can be overridden via URL parameters. */
    private static final int DEFAULT_WIDTH  = 960;
    private static final int DEFAULT_HEIGHT = 540;

    @Override
    public GwtApplicationConfiguration getConfig() {
        GwtApplicationConfiguration cfg =
                new GwtApplicationConfiguration(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        // Allow the canvas to fill the browser window.
        cfg.width  = 0;
        cfg.height = 0;
        return cfg;
    }

    @Override
    public ApplicationListener createApplicationListener() {
        /*
         * Mirrors the call in GameLauncher / Main for LWJGL3:
         *   Forge.getApp(hwInfo, clipboard, deviceAdapter, assetsDir, ...)
         *
         * For the HTML5 target:
         *   - hwInfo   : null  (no hardware-level info in a browser)
         *   - clipboard: GwtClipboard (provided by gdx-backend-gwt)
         *   - adapter  : HtmlAdapter  (browser-specific IDeviceAdapter)
         *   - assetsDir: ""    (assets are served from the web root)
         *   - portrait : false (default to landscape; can be made responsive)
         */
        return Forge.getApp(
                null,
                new GwtClipboard(),
                new HtmlAdapter(),
                /* assetsDir = */ "",
                /* propertyConfig = */ false,
                /* androidOrientation / portrait = */ false,
                /* totalRAM = */ 0,
                /* isTablet = */ true,
                /* androidAPI = */ 0);
    }
}
