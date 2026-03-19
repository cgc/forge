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
 * simply not applicable in a web context.  Methods that are web-incompatible
 * are no-ops with a brief explanation of the web behaviour.
 *
 * <h2>No-changes-outside-forge-gui-teavm strategy</h2>
 * <p>Rather than modifying {@code IDeviceAdapter} or any other shared
 * interface, this module provides compile-time <em>stub replacements</em> for
 * every incompatible library:
 * <ul>
 *   <li><b>{@code org.jupnp.*}</b> – excluded in {@code pom.xml}; replaced by
 *       an empty {@code UpnpServiceConfiguration} stub in
 *       {@code src/main/java/org/jupnp/}.  The UPnP code paths in
 *       {@code FServerManager} are unreachable from the web launcher, so
 *       TeaVM trims them during tree-shaking.</li>
 *   <li><b>{@code io.sentry.*}</b> – excluded in {@code pom.xml}; replaced by
 *       no-op stubs in {@code src/main/java/io/sentry/} and
 *       {@code src/main/java/io/sentry/protocol/}.  Sentry error reporting is
 *       silently dropped on the web target; a separate browser-native solution
 *       (e.g., Sentry's own browser SDK) can be added later.</li>
 * </ul>
 *
 * <p>Because Maven places the current module's compiled classes before
 * transitive-dependency JARs in the TeaVM compilation classpath, these stubs
 * take precedence over the real libraries during both {@code javac} and TeaVM
 * compilation without requiring any changes to the libraries' source modules.
 *
 * <h2>Remaining known issues (no external changes needed)</h2>
 * <ol>
 *   <li><b>{@code java.nio.file.Files} / {@code Paths}</b> – used in
 *       {@code Forge.java} ({@code Files.exists}) and {@code FSkinFont.java}
 *       ({@code Files.newInputStream}).  TeaVM's JS-mode emulation of these
 *       classes is limited: {@code Files.exists} returns {@code false} (safe
 *       fallback), but {@code Files.newInputStream} will throw at runtime.
 *       Fix options (all within {@code forge-gui-teavm}):
 *       <ul>
 *         <li>Provide stub implementations in {@code src/main/java/java/nio/}
 *             (works because TeaVM uses a separate class library mechanism for
 *             its own {@code java.*} emulation layer).</li>
 *         <li>Add a TeaVM {@code ClassHolderTransformer} to
 *             {@code BuildForgeTeaVM.java} that replaces the
 *             {@code Files.newInputStream} call site in {@code FSkinFont}.</li>
 *       </ul></li>
 *   <li><b>Reflection</b> – several screens ({@code NewGameMenu},
 *       {@code LoadGameMenu}, {@code OnlineMenu}, {@code SaveFileData}) use
 *       {@code Class.forName} or {@code getDeclaredMethods} with dynamic
 *       strings.  TeaVM supports limited reflection; these may need review
 *       but can often be resolved by adding the affected classes to TeaVM's
 *       {@code @TeaVMReflectionAccess} annotation or the compiler's
 *       preserved-class list.</li>
 *   <li><b>LibGDX version</b> – the project currently uses gdx 1.13.5; gdx-teavm
 *       1.5.3 requires 1.14.0.  The {@code forge-gui-teavm} POM explicitly
 *       declares gdx 1.14.0 so Maven's nearest-wins rule overrides the 1.13.5
 *       version from {@code forge-gui-mobile} for this module's classpath only,
 *       without touching any other module's POM.</li>
 * </ol>
 */
public class TeaVMAdapter implements IDeviceAdapter {

    /**
     * Browsers are always "connected" from the application's perspective.
     * If a real connectivity check is needed, query {@code navigator.onLine}
     * via a TeaVM {@code @JSBody} call.
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
     * The browser sandbox has no "downloads directory."  File downloads should
     * be triggered via a dynamically-created {@code <a download>} element using
     * a TeaVM {@code @JSBody} call.
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
     * ({@code screen.orientation.lock}); not implemented in this stub.
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
     * is generally blocked by browser security policies.
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
     * JPEG conversion in a browser would use a hidden {@code <canvas>} element
     * via the Web Canvas API.  Not implemented for this stub.
     */
    @Override
    public void convertToJPEG(InputStream input, OutputStream output) throws IOException {
        // No-op.
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
     * extension for controller support; this stub returns an empty list.
     */
    @Override
    public ArrayList<String> getGamepads() {
        return new ArrayList<>();
    }

    /**
     * UPnP is a native networking protocol that has no browser equivalent.
     * Returns {@code null} on the web target; the stub
     * {@code UpnpServiceConfiguration} interface satisfies the type reference
     * at TeaVM-compile time (see class-level Javadoc for details).
     */
    @Override
    public UpnpServiceConfiguration getUpnpPlatformService() {
        return null;
    }

    /** No filesystem permission required in a browser context. */
    @Override
    public boolean needFileAccess() {
        return false;
    }

    @Override
    public void requestFileAcces() { // spelling matches IDeviceAdapter (typo preserved from interface)
        // No-op.
    }

    /**
     * Audio format checking is not meaningful on the web target (audio is
     * handled at a higher level by the Forge sound system, which is disabled
     * for this initial milestone).  The default implementation in
     * {@link IDeviceAdapter} uses {@code java.io.File}; this override avoids
     * that dependency and always returns {@code false} to disable local audio
     * format filtering.
     */
    @Override
    public boolean isSupportedAudioFormat(File file) {
        return false;
    }
}
