package forge.ios;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;

import org.apache.commons.lang3.tuple.Pair;
import org.jupnp.UpnpServiceConfiguration;
import org.robovm.apple.foundation.Foundation;
import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.foundation.NSBundle;
import org.robovm.apple.foundation.NSException;
import org.robovm.apple.foundation.NSString;
import org.robovm.apple.uikit.UIApplication;
import org.robovm.apple.uikit.UIApplicationLaunchOptions;
import org.robovm.apple.uikit.UIPasteboard;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.iosrobovm.DefaultIOSInput;
import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.badlogic.gdx.backends.iosrobovm.IOSFiles;
import com.badlogic.gdx.backends.iosrobovm.IOSInput;

import forge.Forge;
import forge.interfaces.IDeviceAdapter;

public class Main extends IOSApplication.Delegate {

    // Thin NSLog wrapper usable at any point — does not require Gdx.app to be set.
    static void nslog(String msg) {
        Foundation.log("%@", new NSString("[Forge] " + msg));
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
        // Register a Java uncaught-exception handler that converts any unhandled
        // Java exception into an NSException.  Without this, Java exceptions that
        // cross the JNI/ObjC boundary cause a silent process abort — nothing shows
        // up in the device console, making crashes completely invisible.  With it,
        // the full Java stack trace appears in the device log (and in crash reports).
        // This is the standard approach used by production RoboVM+libGDX apps such
        // as Shattered Pixel Dungeon.
        NSException.registerDefaultJavaUncaughtExceptionHandler();

        // On iOS 8+, the app bundle (containing all resources) lives in a separate
        // read-only "Bundle container", while $HOME points to the writable "Data
        // container".  The old localStoragePath-based calculation landed in the Data
        // container and forge could never find its res/ assets.  NSBundle gives the
        // canonical bundle path that works on every iOS version and deployment type.
        final String assetsDir = NSBundle.getMainBundle().getBundlePath() + "/";
        nslog("createApplication: assetsDir=" + assetsDir);
        nslog("createApplication: HOME=" + System.getenv("HOME"));

        final IOSApplicationConfiguration config = new IOSApplicationConfiguration();
        config.useAccelerometer = false;
        config.useCompass = false;
        // Disable audio until OAL/OpenAL is confirmed working on the target device.
        // OALSimpleAudio.sharedInstance() can return null on some configurations;
        // with audio enabled that logs an error but otherwise continues.  If the
        // underlying OpenAL context creation fails it can throw, silently killing
        // the app before any diagnostic output appears.  Re-enable once the app
        // launches successfully.
        config.useAudio = false;
        final ApplicationListener app = Forge.getApp(null, new IOSClipboard(), new IOSAdapter(), assetsDir, false, false, 0, false, 0);
        // Override createInput() so that setupAccelerometer() and setupCompass()
        // are unconditional no-ops.  DefaultIOSInput guards them behind the config
        // flags, but those guards are evaluated at runtime; overriding here
        // eliminates any path to UIAccelerometer.getSharedAccelerometer(), which
        // on iOS 14+ internally initializes CMMotionManager (CoreMotion) and
        // causes a noisy permission warning on physical devices.
        final IOSApplication iosApp = new IOSApplication(app, config) {
            @Override
            protected IOSInput createInput() {
                return new DefaultIOSInput(this) {
                    @Override protected void setupAccelerometer() {}
                    @Override protected void setupCompass() {}
                };
            }
        };
        return iosApp;
    }

    public static void main(String[] args) {
        // NOTE: Gdx.app is NULL here — it is only set inside didFinishLaunching().
        // Do NOT call Gdx.app.log() or any Gdx API before UIApplication.main() returns.
        // Use System.out.println() / System.err.println() for pre-launch diagnostics.
        final NSAutoreleasePool pool = new NSAutoreleasePool();
        try {
            UIApplication.main(args, null, Main.class);
        } catch (Throwable t) {
            // Surface any uncaught exception so it appears in the device console
            // rather than causing a silent process exit with no diagnostics.
            t.printStackTrace(System.err);
            throw t;
        }
        pool.close();
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
            return new IOSFiles().getExternalStoragePath();
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
            return new IOSFiles().local(filename).exists();
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