package forge.teavm;

import forge.interfaces.IDeviceAdapter;
import org.apache.commons.lang3.tuple.Pair;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;

/**
 * Browser-specific implementation of {@link IDeviceAdapter} for the TeaVM
 * web target.
 *
 * <p>Most device-specific features either have browser equivalents or are
 * simply not applicable in a web context.  Each method below documents the
 * appropriate browser-side behaviour and, where necessary, identifies API
 * changes required before the TeaVM build can succeed.
 *
 * <h2>Required API changes before TeaVM compilation succeeds</h2>
 *
 * <h3>1. {@code IDeviceAdapter.getUpnpPlatformService()}</h3>
 * <p>The method signature in {@link IDeviceAdapter} references
 * {@code org.jupnp.UpnpServiceConfiguration}, a type from the jupnp library.
 * {@code org.jupnp} is not available in the browser environment and is not
 * emulated by TeaVM, so any class that imports it will fail to compile.
 * <p><b>Options:</b>
 * <ol>
 *   <li>Extract the UPnP methods into a separate interface
 *       (e.g., {@code IUpnpProvider}) and have non-web adapters implement it;
 *       web adapters remain unaffected.</li>
 *   <li>Change the return type to {@code Object} and cast at call sites.</li>
 *   <li>Replace the concrete jupnp type with a project-owned interface that
 *       acts as an abstraction layer.</li>
 * </ol>
 * <p><b>Affected files:</b>
 * <ul>
 *   <li>{@code forge-gui/src/.../interfaces/IDeviceAdapter.java}</li>
 *   <li>{@code forge-gui-mobile/src/forge/GuiMobile.java}</li>
 *   <li>{@code forge-gui-mobile/src/forge/screens/settings/SettingsPage.java}</li>
 *   <li>All platform adapter implementations (DesktopAdapter, AndroidAdapter,
 *       IosAdapter, etc.)</li>
 * </ul>
 *
 * <h3>2. {@code IDeviceAdapter.isSupportedAudioFormat(File)}</h3>
 * <p>The default method in {@link IDeviceAdapter} uses {@code java.io.File},
 * which is not available in TeaVM's JavaScript target.
 * <p><b>Fix:</b> Change the signature to accept a {@code String} path instead
 * of a {@code File} object.  All call sites pass
 * {@code file.getPath().toLowerCase()} anyway, so this is a safe refactoring.
 * <p><b>Affected files:</b>
 * <ul>
 *   <li>{@code forge-gui/src/.../interfaces/IDeviceAdapter.java}</li>
 *   <li>All call sites of {@code isSupportedAudioFormat}</li>
 * </ul>
 *
 * <h3>3. {@code forge/Forge.java} – Sentry and {@code java.nio.file}</h3>
 * <p>{@code Forge.java} imports {@code io.sentry.*} and uses
 * {@code java.nio.file.Files} / {@code java.nio.file.Paths} at startup.
 * TeaVM does not emulate these libraries.
 * <p><b>Fix:</b>
 * <ul>
 *   <li>Guard Sentry initialisation behind a platform flag, e.g.,
 *       {@code if (!ForgeConstants.IS_WEB) { Sentry.init(...); }}</li>
 *   <li>Replace {@code Files.exists(Paths.get("./res"))} with
 *       {@code Gdx.files.internal("res").exists()} which works on all
 *       LibGDX platforms including the web.</li>
 * </ul>
 * <p><b>Affected files:</b>
 * <ul>
 *   <li>{@code forge-gui-mobile/src/forge/Forge.java}</li>
 * </ul>
 *
 * <h3>4. {@code forge/sound/AudioClip.java} – {@code java.io.File} and
 *         {@code Thread.sleep}</h3>
 * <p>{@code AudioClip.java} uses {@code java.io.File} for file-handle lookup
 * and calls {@code Thread.sleep} to add a playback delay.
 * <p><b>Fix:</b>
 * <ul>
 *   <li>Change the {@code createClip(File)} overload to accept a path
 *       {@code String} and use {@code Gdx.files.absolute(path)}.</li>
 *   <li>Remove the {@code Thread.sleep} call; it is a no-op on the web target
 *       and TeaVM will throw a compilation error for it.  If the delay is
 *       needed, schedule it with {@code Gdx.app.postRunnable}.</li>
 * </ul>
 * <p><b>Affected files:</b>
 * <ul>
 *   <li>{@code forge-gui-mobile/src/forge/sound/AudioClip.java}</li>
 * </ul>
 *
 * <h3>5. {@code forge/util/LibGDXImageFetcher.java} – {@code HttpURLConnection}
 *         and {@code java.nio.file}</h3>
 * <p>Uses {@code java.net.HttpURLConnection} and {@code java.nio.file.Files}
 * for downloading card images.  Both are unavailable in TeaVM.
 * <p><b>Fix:</b> Replace with LibGDX's {@code com.badlogic.gdx.Net.HttpRequest}
 * (works on all LibGDX platforms including web) and write the result via
 * {@code Gdx.files.local(path)}.
 * <p><b>Affected files:</b>
 * <ul>
 *   <li>{@code forge-gui-mobile/src/forge/util/LibGDXImageFetcher.java}</li>
 * </ul>
 *
 * <h3>6. Sentry usage in other classes</h3>
 * <p>{@code io.sentry.Sentry} is also used in:
 * <ul>
 *   <li>{@code forge/adventure/util/SaveFileData.java}</li>
 *   <li>{@code forge/screens/match/views/VCardDisplayArea.java}</li>
 * </ul>
 * Each usage should be guarded by the same {@code IS_WEB} flag.
 *
 * <h3>7. Reflection ({@code Class.forName}, {@code getDeclaredMethods})</h3>
 * <p>The following classes use Java reflection:
 * <ul>
 *   <li>{@code forge/adventure/util/SaveFileData.java}</li>
 *   <li>{@code forge/screens/home/NewGameMenu.java}</li>
 *   <li>{@code forge/screens/home/LoadGameMenu.java}</li>
 *   <li>{@code forge/screens/online/OnlineMenu.java}</li>
 * </ul>
 * TeaVM supports a limited reflection subset.  Each class needs review:
 * {@code Class.forName} with a dynamic string is not supported, but accessing
 * known class objects via {@code MyClass.class} works fine.
 */
public class TeaVMAdapter implements IDeviceAdapter {

    /**
     * Browsers are always "connected" from the application's perspective.
     * If a real connectivity check is needed, query {@code navigator.onLine}
     * via TeaVM's JSO API ({@code @JSBody}).
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
     * Treat all browser clients as tablets so the wide-screen mobile layout
     * is used.  The browser window can be resized freely, so this is a safe
     * default.
     */
    @Override
    public boolean isTablet() {
        return true;
    }

    /**
     * The browser sandbox has no "downloads directory."  File downloads
     * should be triggered via a dynamically-created {@code <a download>}
     * element using TeaVM's JSO API.
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
     * Fetching the changelog from a remote URL requires a CORS-enabled
     * endpoint.  Return an empty string for the web target.
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
     * "Opening" a file is not meaningful in a browser context.  A future
     * implementation could initiate a browser download or open a new tab.
     */
    @Override
    public boolean openFile(String filename) {
        return false;
    }

    /**
     * Orientation control on the web is done via CSS
     * ({@code screen.orientation.lock}); not implemented here.
     */
    @Override
    public void setLandscapeMode(boolean landscapeMode) {
        // No-op: orientation is controlled by the browser / OS.
    }

    /**
     * The Screen Wake Lock API ({@code navigator.wakeLock}) can prevent
     * system sleep on supported browsers.  Not implemented in this stub.
     */
    @Override
    public void preventSystemSleep(boolean preventSleep) {
        // No-op.
    }

    /**
     * Reloading the page ({@code window.location.reload()}) is the closest
     * browser equivalent of a restart.  Use a TeaVM {@code @JSBody} call if
     * this behaviour is actually needed.
     */
    @Override
    public void restart() {
        // No-op stub; implement via JSO if required.
    }

    /**
     * Closing the browser tab programmatically requires a user gesture and
     * is generally blocked by browser security policies.  This is a no-op.
     */
    @Override
    public void exit() {
        // No-op stub.
    }

    /** No splash screen on the web target. */
    @Override
    public void closeSplashScreen() {
        // No-op.
    }

    /**
     * JPEG conversion in a browser would use a hidden {@code <canvas>}
     * element via the Web Canvas API.
     *
     * <p><b>API change required:</b> {@link InputStream} / {@link OutputStream}
     * are available in TeaVM but the underlying I/O abstractions are
     * meaningless in a browser.  If this method is genuinely needed on the web
     * target the signature should be changed to accept a
     * {@code com.badlogic.gdx.graphics.Pixmap} and return a {@code byte[]}.
     */
    @Override
    public void convertToJPEG(InputStream input, OutputStream output) throws IOException {
        // Not implemented for the web target.
    }

    @Override
    public Pair<Integer, Integer> getRealScreenSize(boolean real) {
        return Pair.of(
                com.badlogic.gdx.Gdx.graphics.getWidth(),
                com.badlogic.gdx.Gdx.graphics.getHeight());
    }

    /**
     * The Gamepad API ({@code navigator.getGamepads()}) is available in
     * modern browsers.  gdx-teavm ships a {@code gdx-controllers-teavm}
     * extension for controller support; this stub returns an empty list
     * until that extension is wired up.
     */
    @Override
    public ArrayList<String> getGamepads() {
        return new ArrayList<>();
    }

    /**
     * UPnP is a native networking protocol and is not available in browsers.
     *
     * <p><b>API change required:</b> {@code org.jupnp.UpnpServiceConfiguration}
     * is not emulated by TeaVM and its import will cause a build failure.  See
     * the class-level Javadoc for the recommended fix.  This method always
     * returns {@code null} on the web target.
     */
    @Override
    public UpnpServiceConfiguration getUpnpPlatformService() {
        return null;
    }

    /** No filesystem permission needed in a browser context. */
    @Override
    public boolean needFileAccess() {
        return false;
    }

    @Override
    public void requestFileAcces() { // preserves the typo in IDeviceAdapter
        // No-op.
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>API change required:</b> The default implementation in
     * {@link IDeviceAdapter} uses {@link File}, which is unavailable in TeaVM.
     * This override accepts a file path {@code String} directly once the
     * interface signature is updated.
     *
     * <p>For the web target we only support {@code .ogg} and {@code .mp3}
     * (Howler.js formats); {@code .wav} may work in some browsers but is
     * not recommended.
     */
    @Override
    public boolean isSupportedAudioFormat(File file) {
        // TODO: once IDeviceAdapter.isSupportedAudioFormat(String) is introduced
        //       this override should be removed.
        if (file == null) return false;
        String path = file.getPath().toLowerCase();
        return path.endsWith(".ogg") || path.endsWith(".mp3");
    }
}
