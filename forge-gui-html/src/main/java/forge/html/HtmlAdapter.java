package forge.html;

import forge.interfaces.IDeviceAdapter;
import org.apache.commons.lang3.tuple.Pair;
import org.jupnp.UpnpServiceConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;

/**
 * Browser-specific implementation of {@link IDeviceAdapter}.
 *
 * <p>Most of the desktop/mobile adapter features either have native browser
 * equivalents or are simply not applicable in a web context.  The comments in
 * each method describe what a production implementation would need to do.
 *
 * <p><b>GWT compatibility note:</b> this class will be compiled to JavaScript
 * by the GWT compiler, so only GWT-supported Java APIs may be used here.
 * Several types referenced by {@link IDeviceAdapter} (e.g.
 * {@link UpnpServiceConfiguration}) are <em>not</em> GWT-compatible; their
 * methods return {@code null} / no-op here and will need to be decoupled from
 * the interface before the full module can be GWT-compiled.
 */
public class HtmlAdapter implements IDeviceAdapter {

    /**
     * Browsers are always "connected" from the application's perspective;
     * actual network checks can be done via the {@code navigator.onLine}
     * JavaScript property using JSNI if needed.
     */
    @Override
    public boolean isConnectedToInternet() {
        return true;
    }

    /** Wi-Fi vs cellular is not distinguishable from a browser context. */
    @Override
    public boolean isConnectedToWifi() {
        return true;
    }

    /**
     * Treat all browser clients as tablets (wide-screen layout) because the
     * mobile UI already adapts well to larger displays and the browser window
     * can be resized freely.
     */
    @Override
    public boolean isTablet() {
        return true;
    }

    /**
     * The browser sandbox has no concept of a "downloads directory."
     * File downloads should be triggered via the browser's native download
     * mechanism (e.g. a dynamically created {@code <a download>} element).
     */
    @Override
    public String getDownloadsDir() {
        return "";
    }

    @Override
    public String getVersionString() {
        return forge.util.BuildInfo.getVersionString();
    }

    /**
     * Change-log fetching would need to go through a CORS-enabled endpoint or
     * a server-side proxy because browsers block cross-origin XHR by default.
     */
    @Override
    public String getLatestChanges(String commitsAtom, Date buildDateOriginal, Date maxDate) {
        return "";
    }

    @Override
    public String getReleaseTag(String releaseAtom) {
        return "";
    }

    /**
     * "Opening a file" is not meaningful in a browser context.  A production
     * implementation could initiate a download or show the file contents in a
     * new browser tab.
     */
    @Override
    public boolean openFile(String filename) {
        return false;
    }

    /** Orientation changes in a browser are handled via CSS media queries. */
    @Override
    public void setLandscapeMode(boolean landscapeMode) {
        // No-op: orientation is controlled by the browser / OS.
    }

    /** System sleep prevention is not available from a browser context. */
    @Override
    public void preventSystemSleep(boolean preventSleep) {
        // No-op.
    }

    /**
     * Reloading the page is the closest browser equivalent of "restart."
     * Use JavaScript's {@code window.location.reload()} via JSNI if needed.
     */
    @Override
    public void restart() {
        // No-op stub; see JavaDoc above.
    }

    /**
     * Closing a browser tab from inside the page requires user gesture
     * permission (browser security restriction).  In practice this is a
     * no-op; the user closes the tab themselves.
     */
    @Override
    public void exit() {
        // No-op stub.
    }

    /** No splash screen to close on the HTML5 target. */
    @Override
    public void closeSplashScreen() {
        // No-op.
    }

    /**
     * JPEG conversion in a browser context would use a hidden {@code <canvas>}
     * element.  In a GWT/libGDX context this path is unlikely to be exercised
     * because asset handling is different on the web.
     *
     * <p><b>Note:</b> {@link InputStream}/{@link OutputStream} are not
     * available in GWT.  This signature will need to change (e.g. accept a
     * {@code Pixmap} and return a byte array) before GWT compilation succeeds.
     */
    @Override
    public void convertToJPEG(InputStream input, OutputStream output) throws IOException {
        // Not implemented for HTML5 target.
    }

    @Override
    public Pair<Integer, Integer> getRealScreenSize(boolean real) {
        return Pair.of(
                com.badlogic.gdx.Gdx.graphics.getWidth(),
                com.badlogic.gdx.Gdx.graphics.getHeight());
    }

    /** Gamepads/controllers are not supported on the HTML5 target. */
    @Override
    public ArrayList<String> getGamepads() {
        return new ArrayList<>();
    }

    /**
     * UPnP is a native networking protocol and is not available in browsers.
     * This will always return {@code null}.
     *
     * <p><b>Note:</b> {@link UpnpServiceConfiguration} is not GWT-compatible
     * and this import will cause a GWT compilation error.  The method (and the
     * import) should be extracted behind a GWT deferred-binding interface
     * before this module can be fully compiled.
     */
    @Override
    public UpnpServiceConfiguration getUpnpPlatformService() {
        return null;
    }

    /** The browser sandbox does not require explicit file-access permission. */
    @Override
    public boolean needFileAccess() {
        return false;
    }

    @Override
    public void requestFileAcces() { // method name matches the typo in IDeviceAdapter
        // No-op.
    }
}
