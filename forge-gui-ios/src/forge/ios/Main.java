package forge.ios;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;

import org.apache.commons.lang3.tuple.Pair;
import org.jupnp.UpnpServiceConfiguration;
import org.robovm.apple.coregraphics.CGRect;
import org.robovm.apple.foundation.Foundation;
import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.foundation.NSBundle;
import org.robovm.apple.foundation.NSException;
import org.robovm.apple.foundation.NSString;
import org.robovm.apple.uikit.UIApplication;
import org.robovm.apple.uikit.UIApplicationLaunchOptions;
import org.robovm.apple.uikit.UIPasteboard;
import org.robovm.apple.uikit.UIScreen;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.iosrobovm.DefaultIOSInput;
import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.badlogic.gdx.backends.iosrobovm.IOSFiles;
import com.badlogic.gdx.backends.iosrobovm.IOSInput;
import com.badlogic.gdx.backends.iosrobovm.IOSScreenBounds;
import org.robovm.apple.glkit.GLKViewDrawableDepthFormat;

import forge.Forge;
import forge.gui.GuiBase;
import forge.interfaces.IDeviceAdapter;

public class Main extends IOSApplication.Delegate {

    // Thin NSLog wrapper usable at any point — does not require Gdx.app to be set.
    static void nslog(String msg) {
        Foundation.log("%@", new NSString("[Forge] " + msg));
    }

    /** Logs the top-level contents of {@code path} at DEBUG level via nslog. */
    private static void logDir(String label, String path) {
        File dir = new File(path);
        nslog(label + " exists=" + dir.exists() + " isDir=" + dir.isDirectory());
        if (dir.isDirectory()) {
            String[] entries = dir.list();
            if (entries == null) {
                nslog(label + " list()=null (permission denied?)");
            } else {
                nslog(label + " entries(" + entries.length + ")=" + java.util.Arrays.toString(entries));
            }
        }
    }

    @Override
    public boolean didFinishLaunching(UIApplication application, UIApplicationLaunchOptions launchOptions) {
        // Wrap the entire launch sequence so that any Java exception is printed to
        // the device console via NSLog before the process aborts.  Without this
        // wrapper, an uncaught Java exception crossing the JNI/ObjC boundary
        // produces a silent abort — nothing appears in the device console and
        // SpringBoard reports only "Scene create failed (null)".
        try {
            nslog("didFinishLaunching: start");
            boolean result = super.didFinishLaunching(application, launchOptions);
            nslog("didFinishLaunching: complete, result=" + result);
            return result;
        } catch (Throwable t) {
            nslog("didFinishLaunching: EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            throw t;
        }
    }

    @Override
    protected IOSApplication createApplication() {
        nslog("createApplication(): building IOSApplication");

        // On iOS 8+, the app bundle (containing all resources) lives in a separate
        // read-only "Bundle container", while $HOME points to the writable "Data
        // container".  The old localStoragePath-based calculation landed in the Data
        // container and forge could never find its res/ assets.  NSBundle gives the
        // canonical bundle path that works on every iOS version and deployment type.
        final String assetsDir = NSBundle.getMainBundle().getBundlePath() + "/";
        nslog("createApplication: assetsDir=" + assetsDir);
        nslog("createApplication: HOME=" + System.getenv("HOME"));

        // ── Directory diagnostics ────────────────────────────────────────────────
        // Log the bundle layout so we can confirm that card-image directories are
        // present in the build.  These messages appear in Console.app and Xcode's
        // device log even on release builds with no debugger attached.
        logDir("bundle", assetsDir);
        logDir("bundle/res", assetsDir + "res");
        logDir("bundle/res/pics", assetsDir + "res/pics");
        logDir("bundle/res/pics/cards", assetsDir + "res/pics/cards");

        // Also log the writable data-container paths where the user's downloaded
        // card images would live after an in-app download.
        String home = System.getenv("HOME");
        if (home != null) {
            logDir("HOME/Documents", home + "/Documents");
            logDir("HOME/Documents/pics", home + "/Documents/pics");
            logDir("HOME/Documents/pics/cards", home + "/Documents/pics/cards");
        }

        final IOSApplicationConfiguration config = new IOSApplicationConfiguration();
        config.useAccelerometer = false;
        config.useCompass = false;
        // Disable the depth buffer: Forge is a pure-2D app and never uses depth
        // testing, so allocating a 16-bit depth renderbuffer (the GLKit default)
        // wastes VRAM and — on some iOS/Metal driver combinations — can cause the
        // framebuffer to be cleared to opaque black on each render pass instead of
        // transparent.  Shattered Pixel Dungeon (another libGDX/iOS title) sets
        // this to None for the same reason.
        config.depthFormat = GLKViewDrawableDepthFormat.None;
        // Audio intentionally disabled: OALSimpleAudio.sharedInstance() and all other
        // ObjectAL ObjC-bridge methods are native trampolines that require the ObjectAL
        // framework to be fully registered with the ObjC runtime before use.  On the
        // iOS simulator the trampoline can resolve to null, causing a crash at
        // pc=0x0 on the background audio thread (Thread 34) before any UI appears.
        // Re-enable only after confirming stable launch on a physical device.
        config.useAudio = false;
        boolean isLandscape = false;
        nslog("createApplication: calling Forge.getApp()");
        final ApplicationListener app = Forge.getApp(null, new IOSClipboard(), new IOSAdapter(assetsDir), assetsDir, false, !isLandscape, 0, false, 0);
        nslog("createApplication: Forge.getApp() returned " + (app == null ? "null" : app.getClass().getName()));
        // The generic isUsingAppDirectory check in Forge.getApp() matches the Android
        // package name ("forge.app") in the OBB path, but the iOS bundle is named
        // "forge.ios.Main.app" which does not match that substring.  Override it here
        // so that iOS always behaves as an app-directory build: profile file is not read
        // from the read-only bundle, and the Settings UI hides the path-configuration
        // options that only make sense on Android/desktop.
        GuiBase.setUsingAppDirectory(true);
        // Override createInput() so that setupAccelerometer() and setupCompass()
        // are unconditional no-ops.  DefaultIOSInput guards them behind the config
        // flags, but those guards are evaluated at runtime; overriding here
        // eliminates any path to UIAccelerometer.getSharedAccelerometer(), which
        // on iOS 14+ internally initializes CMMotionManager (CoreMotion) and
        // causes a noisy permission warning on physical devices.
        final IOSApplication iosApp = new IOSApplication(app, config) {
            /**
             * Forge always hides the status bar ({@code prefersStatusBarHidden} returns
             * {@code true}).  The default libGDX implementation subtracts
             * {@code UIApplication.statusBarFrame.height} from the screen height; on iOS
             * 13–15 that value can be non-zero even after the bar is hidden, so the height
             * returned here differs from the one reported after
             * {@code viewDidLayoutSubviews} settles.  That difference causes libGDX to call
             * {@code Forge.resize()} with a larger height, but {@code Forge.resize()} does
             * not update the cached {@code screenWidth}/{@code screenHeight} fields used by
             * {@code Forge.render()} → {@code Graphics.begin()}.  The resulting mismatch
             * between {@code regionHeight} and {@code Gdx.graphics.getHeight()} shifts the
             * scissor rectangle upward, clipping the top of every scroll-pane, list-view
             * and drop-down container.
             *
             * <p>Fix: always report the full {@link UIScreen#getMainScreen()} bounds so
             * that the dimensions seen at {@code create()} time are already final and
             * {@code viewDidLayoutSubviews} does not trigger a second {@code resize()}
             * call with different dimensions.
             */
            @Override
            protected IOSScreenBounds computeBounds() {
                CGRect screen = UIScreen.getMainScreen().getBounds();
                double nativeScale = UIScreen.getMainScreen().getNativeScale();
                int w = (int) Math.round(screen.getWidth());
                int h = (int) Math.round(screen.getHeight());
                int backW = (int) Math.round(w * nativeScale);
                int backH = (int) Math.round(h * nativeScale);
                nslog("computeBounds: w=" + w + " h=" + h
                        + " backW=" + backW + " backH=" + backH
                        + " scale=" + nativeScale);
                return new IOSScreenBounds(0, 0, w, h, backW, backH);
            }

            @Override
            protected IOSInput createInput() {
                return new DefaultIOSInput(this) {
                    @Override protected void setupAccelerometer() {}
                    @Override protected void setupCompass() {}
                };
            }
        };
        nslog("createApplication: IOSApplication created, returning");
        return iosApp;
    }

    public static void main(String[] args) {
        // ── Breadcrumb 1: file write ────────────────────────────────────────────
        // Written before any ObjC call.  If the JVM reaches main() this file will
        // exist in the app's Documents directory; retrieve it via Xcode → Devices &
        // Simulators → Download Container, or via the iOS Files app.  Its presence
        // confirms the JVM started and main() was called even after a later crash.
        try {
            String home = System.getenv("HOME");
            if (home != null) {
                new java.io.FileOutputStream(home + "/Documents/forge_boot.txt").close();
            }
        } catch (Throwable ignored) {}

        // ── Breadcrumb 2: stdout ────────────────────────────────────────────────
        // System.out is remapped to os_log by RoboVM; visible in Console.app and
        // in Xcode's debug console.  Use this as a cross-check against nslog().
        System.out.println("[Forge] main() entered");

        // Create the autorelease pool before the first NSObject allocation.
        // NOTE: Gdx.app is NULL here; it is only set inside didFinishLaunching().
        final NSAutoreleasePool pool = new NSAutoreleasePool();

        // ── Breadcrumb 3: NSLog ─────────────────────────────────────────────────
        // Foundation.log() calls NSLog() which writes to the unified logging system
        // (os_log).  Visible in Console.app on macOS when the device is connected,
        // even during a crash (os_log is synchronous).
        nslog("main() entered: Forge iOS starting");

        // Register the uncaught-exception handler before UIApplication.main() so
        // that any Java exception thrown during UIKit's own startup (before
        // createApplication() runs) also produces a full stack trace in the log.
        // This is the standard pattern used by production RoboVM+libGDX apps.
        NSException.registerDefaultJavaUncaughtExceptionHandler();

        try {
            UIApplication.main(args, null, Main.class);
        } catch (Throwable t) {
            // Surface any uncaught exception so it appears in the system log.
            nslog("main() EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
            throw t;
        } finally {
            pool.close();
        }
    }

    //special clipboard that works on iOS
    private static final class IOSClipboard implements com.badlogic.gdx.utils.Clipboard {
        @Override
        public boolean hasContents() {
            return UIPasteboard.getGeneralPasteboard().toString().length() > 0;
        }

        @Override
        public String getContents() {
            return UIPasteboard.getGeneralPasteboard().getString();
        }

        @Override
        public void setContents(final String contents0) {
            UIPasteboard.getGeneralPasteboard().setString(contents0);
        }
    }

    private static final class IOSAdapter implements IDeviceAdapter {
        private final String bundlePath;

        IOSAdapter(String bundlePath) {
            this.bundlePath = bundlePath;
        }

        @Override
        public boolean isConnectedToInternet() {
            return true;
        }

        @Override
        public boolean isConnectedToWifi() {
            return true;
        }

        @Override
        public String getDownloadsDir() {
            String dir = new IOSFiles().getExternalStoragePath();
            nslog("IOSAdapter.getDownloadsDir()=" + dir);
            return dir;
        }

        @Override
        public String getVersionString() {
            return "0.0";
        }

        @Override
        public String getLatestChanges(String commitsAtom, Date buildDateOriginal, Date maxDate) {
            return "";
        }

        @Override
        public String getReleaseTag(String releaseAtom) {
            return "";
        }

        @Override
        public boolean openFile(final String filename) {
            // Check both the writable data container and the read-only bundle so we
            // can diagnose which location the app is actually searching.
            boolean localExists = new IOSFiles().local(filename).exists();
            boolean bundleExists = new File(bundlePath + filename).exists();
            nslog("IOSAdapter.openFile(" + filename + "): local=" + localExists + " bundle=" + bundleExists);
            return localExists;
        }

        @Override
        public void setLandscapeMode(final boolean landscapeMode) {
            // TODO implement this
        }

        @Override
        public void preventSystemSleep(boolean preventSleep) {
            // TODO implement this
        }

        @Override
        public boolean isTablet() {
            return Gdx.graphics.getWidth() > Gdx.graphics.getHeight();
        }

        @Override
        public void restart() {
            // Not possible on iOS
        }

        @Override
        public void exit() {
            // Not possible on iOS
        }

        @Override
        public void closeSplashScreen() {
            //only for desktop mobile-dev
        }

        @Override
        public void convertToJPEG(InputStream input, OutputStream output) throws IOException {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = input.read(buffer)) != -1) {
                output.write(buffer, 0, len);
            }
        }

        @Override
        public Pair<Integer, Integer> getRealScreenSize(boolean real) {
            return Pair.of(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        }

        @Override
        public ArrayList<String> getGamepads() {
            return new ArrayList<>();
        }

        @Override
        public UpnpServiceConfiguration getUpnpPlatformService() {
            // not used
            return null;
        }

        @Override
        public boolean needFileAccess() {
            return false;
        }

        @Override
        public void requestFileAcces() {

        }
    }
}