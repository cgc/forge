package forge.ios;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.Semaphore;

import org.apache.commons.lang3.tuple.Pair;
import org.jupnp.UpnpServiceConfiguration;
import org.robovm.apple.coregraphics.CGRect;
import org.robovm.apple.dispatch.DispatchQueue;
import org.robovm.apple.foundation.Foundation;
import org.robovm.apple.foundation.NSAutoreleasePool;
import org.robovm.apple.foundation.NSBundle;
import org.robovm.apple.foundation.NSException;
import org.robovm.apple.foundation.NSProcessInfo;
import org.robovm.apple.foundation.NSString;
import org.robovm.apple.foundation.NSThread;
import org.robovm.apple.glkit.GLKViewDrawableColorFormat;
import org.robovm.apple.glkit.GLKViewDrawableDepthFormat;
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
import com.badlogic.gdx.backends.iosrobovm.IOSGraphics;
import com.badlogic.gdx.backends.iosrobovm.IOSInput;
import com.badlogic.gdx.backends.iosrobovm.IOSScreenBounds;
import com.badlogic.gdx.graphics.glutils.HdpiMode;

import forge.Forge;
import forge.gamemodes.limited.DraftRankCache;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.interfaces.IDeviceAdapter;
import forge.localinstance.properties.ForgePreferences.FPref;

public class Main extends IOSApplication.Delegate {

    /**
     * Class-literal reference to {@code TransformerFactoryImpl} — belt-and-suspenders
     * companion to {@link #preWarmXalan()}.
     *
     * <p>robovmx's libcore {@link javax.xml.transform.TransformerFactory#newInstance()}
     * does a {@code Class.forName("org.apache.xalan.processor.TransformerFactoryImpl")}
     * fallback.  That class lives in the {@code xalan:xalan:2.7.3} Maven dependency JAR.
     * The {@code robovm.xml} {@code forceLinkClasses} entry for it is sometimes ignored
     * for classes in third-party dependency JARs (RoboVM/Soot may silently skip classes
     * from a JAR when it encounters any compile issue in the same JAR).
     *
     * <p>An {@code LDC <class>} bytecode instruction registers the class in the AOT
     * binary's class table so that {@code Class.forName()} can find it.  However,
     * it does <em>not</em> make any of the class's <em>methods</em> reachable in
     * Soot's call-graph analysis, so the constructor's native code is never compiled.
     * When {@code TransformerFactory.newInstance()} subsequently calls
     * {@code clazz.newInstance()} via reflection the constructor is missing and
     * RoboVM throws {@code NoClassDefFoundError}.
     *
     * <p>The real fix is {@link #preWarmXalan()}, which contains a direct
     * {@code new TransformerFactoryImpl()} call that forces Soot to AOT-compile the
     * constructor (and all methods it transitively calls).  This literal is kept
     * alongside it as a defence-in-depth measure.
     */
    @SuppressWarnings("unused")
    private static final Class<?> XALAN_TF_CLASS =
            org.apache.xalan.processor.TransformerFactoryImpl.class;

    /**
     * Class-literal reference to {@code ToXMLStream} — forces Soot to compile the
     * XML serializer output class into the AOT binary.
     *
     * <p>When a {@code Transformer} performs an XML transform (e.g.
     * {@link forge.util.XmlUtil#saveDocument}), Xalan's
     * {@code TransformerIdentityImpl.createResultContentHandler()} calls
     * {@code SerializerFactory.getSerializer(props)} which loads the content-handler
     * class by name from the output-method properties:
     * {@code "org.apache.xml.serializer.ToXMLStream"}.  That string is in a
     * {@code .properties} resource file — there is no bytecode reference to the
     * class anywhere in the call graph, so Soot never AOT-compiles it.
     *
     * <p>At runtime, {@code SerializerFactory} calls
     * {@code Class.forName("org.apache.xml.serializer.ToXMLStream")} from Xalan app
     * code.  Unlike the {@code TransformerFactory} bootstrap-classloader issue, this
     * {@code Class.forName()} runs in app-classloader context and CAN see app classes
     * — but only if the class was compiled into the binary.  Without this literal (and
     * the direct {@code new} in {@link #preWarmXalan()}), the class is absent and
     * {@code SerializerFactory} throws
     * {@code WrappedRuntimeException: org.apache.xml.serializer.ToXMLStream}.
     */
    @SuppressWarnings("unused")
    private static final Class<?> XALAN_TOXML_CLASS =
            org.apache.xml.serializer.ToXMLStream.class;

    /**
     * Pre-warms Xalan's {@code TransformerFactoryImpl} and serializer output classes
     * so that Soot's call-graph analysis includes their constructors in the AOT binary.
     *
     * <p>Called once at the start of {@link #createApplication()} before any Forge
     * code runs.  A direct {@code new TransformerFactoryImpl()} emits
     * {@code INVOKESPECIAL <init>} bytecode, which Soot follows transitively —
     * compiling the constructor and every method it calls into native code.  Without
     * this, only the class-table entry exists (from the {@code LDC <class>} literal
     * above) and reflection-based instantiation in
     * {@code TransformerFactory.newInstance()} fails with
     * {@code NoClassDefFoundError} even though the class is registered.
     *
     * <p>Similarly, a direct {@code new ToXMLStream()} forces Soot to compile the
     * serializer class that {@code SerializerFactory.getSerializer()} loads by name
     * for XML output transforms.
     *
     * <p>Any exception is swallowed; if Xalan is truly broken on this device we
     * prefer to discover the failure at the actual XML call site rather than
     * aborting the launch sequence.
     */
    private void preWarmXalan() {
        try {
            org.apache.xalan.processor.TransformerFactoryImpl tf =
                    new org.apache.xalan.processor.TransformerFactoryImpl();
            // Store the class in StreamUtil so that StreamUtil.transformerFactoryNewInstance()
            // can create new instances without going through Class.forName() from libcore.
            // TransformerFactory.newInstance() (in robovmx's libcore) uses Class.forName()
            // from the bootstrap classloader context, which cannot see Xalan (an app dep).
            // Using the Class reference obtained here (from app-code context, where the AOT
            // linker resolves the reference directly) avoids that broken lookup path entirely.
            forge.util.StreamUtil.transformerFactoryClass = tf.getClass();
            nslog("preWarmXalan: registered " + tf.getClass().getName());
        } catch (Throwable t) {
            nslog("preWarmXalan: TransformerFactoryImpl init failed: " + t);
        }
        // Pre-warm ToXMLStream: SerializerFactory.getSerializer() loads this class by name
        // from a .properties resource file.  Without a direct reference here Soot never
        // compiles it and Class.forName() fails at runtime with WrappedRuntimeException.
        try {
            new org.apache.xml.serializer.ToXMLStream();
            nslog("preWarmXalan: ToXMLStream compiled OK");
        } catch (Throwable t) {
            nslog("preWarmXalan: ToXMLStream init failed: " + t);
        }
    }

    /**
     * IOSGraphics subclass that makes {@code requestRendering()} and
     * {@code setContinuousRendering()} safe to call from any thread by dispatching
     * the UIKit calls to the main GCD queue.
     *
     * <p>In robovmx (experiment/2-libcore-10) ObjC bridge calls use direct
     * function-pointer (IMP) dispatch instead of {@code objc_msgSend}.  As a result
     * robovmx no longer silently marshals UIKit calls to the main thread the way
     * MobiVM 2.3.23 did.  {@link IOSGraphics#requestRendering()} calls
     * {@code viewController.setPaused(false)} (a {@code GLKViewController} UIKit
     * method) when {@code isContinuous == false}, and
     * {@link IOSGraphics#setContinuousRendering(boolean)} calls
     * {@code view.setPaused(!isContinuous)} (a {@code GLKView} UIKit method).
     * If either call is made from a background thread:
     * <ul>
     *   <li><b>iOS simulator</b> – resolves to a null ObjC trampoline → {@code pc=0x0}
     *       crash (Thread N Crashed: 0 ??? 0x0).</li>
     *   <li><b>Physical device</b> – can cause {@code EXC_BAD_ACCESS} or silently
     *       ignore the call, leaving the GL render loop in the wrong state and
     *       causing any subsequent {@code WaitRunnable.invokeAndWait()} to deadlock.</li>
     * </ul>
     *
     * <p>This subclass intercepts both methods and, when called from a non-main
     * thread, posts the actual work to the main GCD queue so that
     * {@code viewController.setPaused()} / {@code view.setPaused()} are always
     * called from the correct thread.
     */
    private static final class SafeIOSGraphics extends IOSGraphics {
        /**
         * @param app      the owning {@link IOSApplication}
         * @param config   application configuration (passed through to the base class)
         * @param input    iOS input handler (passed through to the base class)
         * @param useGLES30 {@code true} to request an OpenGL ES 3.0 context
         */
        SafeIOSGraphics(IOSApplication app, IOSApplicationConfiguration config,
                        IOSInput input, boolean useGLES30) {
            super(app, config, input, useGLES30);
        }

        /**
         * Thread-safe override of {@link IOSGraphics#requestRendering()}.
         *
         * <p>If called from the main thread the parent implementation is invoked
         * directly (no overhead).  If called from any other thread the call is
         * posted to the main GCD queue so that
         * {@code viewController.setPaused(false)} — the UIKit call that actually
         * unpauses the render loop — always executes on the main thread.
         */
        @Override
        public void requestRendering() {
            if (NSThread.getCurrentThread().isMainThread()) {
                doRequestRendering();
            } else {
                // Dispatch to main thread: viewController.setPaused() is UIKit and
                // must always run on the main thread in robovmx's direct-IMP mode.
                DispatchQueue.getMainQueue().async(this::doRequestRendering);
            }
        }

        /**
         * Calls {@link IOSGraphics#requestRendering()} on the current thread.
         * Separated from {@link #requestRendering()} so it can be used as a
         * method-reference in the GCD dispatch block (Java lambdas cannot capture
         * {@code super} from an enclosing class).
         */
        private void doRequestRendering() {
            super.requestRendering();
        }

        /**
         * Thread-safe override of {@link IOSGraphics#setContinuousRendering(boolean)}.
         *
         * <p>In robovmx's direct function-pointer (IMP) dispatch mode,
         * {@code IOSGraphics.setContinuousRendering()} calls
         * {@code view.setPaused(!isContinuous)} — a {@code GLKView} UIKit method
         * that must always execute on the main thread.  Calling it from a background
         * thread resolves to a null ObjC trampoline on the iOS simulator
         * ({@code pc=0x0} crash) and causes an {@code EXC_BAD_ACCESS} on physical
         * devices.  This override mirrors the pattern used for
         * {@link #requestRendering()}: dispatch to the main GCD queue whenever the
         * caller is not already on the main thread.
         */
        @Override
        public void setContinuousRendering(boolean isContinuous) {
            if (NSThread.getCurrentThread().isMainThread()) {
                doSetContinuousRendering(isContinuous);
            } else {
                final boolean cont = isContinuous;
                DispatchQueue.getMainQueue().async(() -> doSetContinuousRendering(cont));
            }
        }

        /**
         * Calls {@link IOSGraphics#setContinuousRendering(boolean)} on the current
         * thread.  Separated from {@link #setContinuousRendering(boolean)} so it can
         * be used as a lambda in the GCD dispatch block.
         */
        private void doSetContinuousRendering(boolean isContinuous) {
            super.setContinuousRendering(isContinuous);
        }
    }

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
            // Disable CardRelationMatrixGenerator on iOS (~10% startup saving).
            //
            // WHY HERE (not in createApplication()):
            //   createApplication() runs before IOSApplication.didFinishLaunching(),
            //   which means Gdx.app is still null.  GuiMobile.isRunningOnDesktop()
            //   returns true when Gdx.app==null, causing ForgeConstants.<clinit> to
            //   take the desktop code path and throw RuntimeException("cannot
            //   determine OS…"), poisoning ForgeConstants permanently with
            //   ExceptionInInitializerError.
            //
            // WHY THIS WORKS:
            //   super.didFinishLaunching() calls IOSApplication.didFinishLaunching()
            //   → app.create() → Forge.create().  Forge.create() calls
            //   getForgePreferences() on the MAIN THREAD at lines 237–240 (before
            //   the background DB-load thread is spawned at line 348).  This creates
            //   the ForgePreferences singleton correctly (Gdx.app is set, ForgeConstants
            //   initialises with iOS paths).  By the time super.didFinishLaunching()
            //   returns here, the singleton already exists.
            //
            // TIMING vs. THE BACKGROUND THREAD:
            //   The background thread (started by Forge.create() line 348) must execute
            //   AssetsDownloader.checkForUpdates() → FModel.initialize() → ImageKeys
            //   → Lang → Localizer → ... before reaching the DECKGEN_CARDBASED check
            //   at FModel.java line 272.  That is ~100 ms of work.  Our setPref()
            //   here is PreferencesStore.setPref() = a single HashMap.put() (no I/O,
            //   no locks) on the already-running main thread — it wins the race reliably.
            //
            // CardRelationMatrixGenerator.initialize() is gated behind DECKGEN_CARDBASED.
            // The user can re-enable it in Settings → Preferences; it will be honoured
            // from the next launch.
            try {
                GuiBase.getForgePrefs().setPref(FPref.DECKGEN_CARDBASED, "false");
                nslog("didFinishLaunching: DECKGEN_CARDBASED=false (CardRelationMatrix deferred)");
            } catch (Throwable t) {
                nslog("didFinishLaunching: DECKGEN_CARDBASED override failed: " + t);
            }
            nslog("didFinishLaunching: complete, result=" + result);
            return result;
        } catch (Throwable t) {
            nslog("didFinishLaunching: EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            throw t;
        }
    }

    /**
     * Called by UIKit when the OS is running low on memory.  Logs the Java heap
     * and extended Mach vm_info (physical footprint, GPU footprint, etc.) via
     * NSLog so that the warning appears in Console.app correlated with the crash
     * log.
     *
     * <p>The default libGDX handler (called via {@code super}) prints "Received
     * memory warning." which is what was previously visible in the logs.  Adding
     * our own logging before the super-call gives us the actual memory numbers
     * at warning time, making it possible to see how much headroom was left
     * before the eventual jetsam kill.
     */
    @Override
    public void didReceiveMemoryWarning(UIApplication application) {
        // logHeap emits Java heap + phys_footprint (physicalFootprintMBSupplier)
        // + full vm_info breakdown (vmInfoLineSupplier) wired up in createApplication().
        DraftRankCache.logHeap("memory-warning");
        super.didReceiveMemoryWarning(application);
    }

    @Override
    protected IOSApplication createApplication() {
        nslog("createApplication(): building IOSApplication");

        // Pre-warm Xalan so that Soot AOT-compiles TransformerFactoryImpl's
        // constructor.  A class literal alone (XALAN_TF_CLASS above) only adds the
        // class to the binary's class table; it does not make the constructor
        // reachable in the call graph.  Without this call, every invocation of
        // TransformerFactory.newInstance() → clazz.newInstance() crashes with
        // NoClassDefFoundError because the constructor's native code was never
        // compiled.
        preWarmXalan();

        // Throttle concurrent PixmapPacker instances during FSkinFont.preloadAll().
        // N=2 lets the background thread rasterize the *next* font size while the
        // EDT uploads the *current* one, giving CPU/GPU overlap while capping peak
        // PixmapPacker memory to ~2× the per-font cost (vs ~65× with no throttle).
        FThreads.configureEdtThrottle(new Semaphore(2));

        // Wire up the Mach memory-info suppliers so that DraftRankCache.logHeap()
        // reports the actual OS-level memory that iOS jetsam monitors alongside
        // the Java heap numbers.
        //   physicalFootprintMBSupplier — fast REV1 read of phys_footprint only
        //   vmInfoLineSupplier          — REV3 read with graphics/ledger fields
        DraftRankCache.physicalFootprintMBSupplier = MachMemInfo::getPhysicalFootprintMB;
        DraftRankCache.vmInfoLineSupplier = MachMemInfo::getVmInfoLine;

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
        // Explicitly request RGBA8888 as the drawable color format.
        //
        // On physical iOS devices the GLKit/Metal translation layer defaults to
        // BGRAFormatPixel32 (BGRA byte order) for the color renderbuffer.  OpenGL
        // textures uploaded as GL_RGBA are stored with the correct RGBA byte order
        // in the GPU, but when they are blitted to the BGRA drawable surface the
        // red and blue channels are swapped — making blue UI elements appear as
        // orange/gold and vice-versa.  The iOS Simulator does not exhibit this bug
        // because macOS uses the x86/ARM host byte order for the drawable surface.
        //
        // Setting colorFormat to RGBA8888 tells GLKit to allocate the renderbuffer
        // as MTLPixelFormatRGBA8Unorm, matching the byte order of GL_RGBA texture
        // uploads and eliminating the channel swap across the entire rendering
        // pipeline without any per-texture or per-shader workarounds.
        //
        // This is the same configuration that Shattered Pixel Dungeon
        // (another libGDX/MobiVM title) uses to solve the identical problem
        // (their MGLDrawableColorFormat.RGBA8888 maps to the same underlying
        // Metal pixel format via their MetalANGLE backend).
        config.colorFormat = GLKViewDrawableColorFormat.RGBA8888;
        // Disable the depth buffer: Forge is a pure-2D app and never uses depth
        // testing, so allocating a 16-bit depth renderbuffer (the GLKit default)
        // wastes VRAM and — on some iOS/Metal driver combinations — can cause the
        // framebuffer to be cleared to opaque black on each render pass instead of
        // transparent.  Shattered Pixel Dungeon (another libGDX/iOS title) sets
        // this to None for the same reason.
        config.depthFormat = GLKViewDrawableDepthFormat.None;
        // Copied from Shattered Pixel Dungeon
        config.hdpiMode = HdpiMode.Pixels;
        // Audio intentionally disabled while startup is being stabilised.
        //
        // Background — robovmx and direct function-pointer dispatch:
        // robovmx 10.x compiles ObjC bridge calls to direct function-pointer (IMP) calls
        // rather than going through objc_msgSend's thread-agnostic dispatch table.  This is
        // faster, but it means robovmx no longer silently marshals UIKit calls to the main
        // thread the way MobiVM 2.3.23 did.  Any ObjC UIKit method called from a background
        // thread now either crashes (simulator: null trampoline → pc=0x0) or silently fails
        // (device: UIKit checks the thread internally and aborts the operation).
        //
        // Scope assessment — what robovmx's direct dispatch impacts in Forge:
        //   • The primary Forge bg-thread UIKit call paths are:
        //       bg thread → Gdx.app.postRunnable() → IOSApplication.postRunnable()
        //           → IOSGraphics.requestRendering()
        //           → (when isContinuous==false) viewController.setPaused(false)  ← UIKit
        //       any thread → Gdx.graphics.setContinuousRendering(boolean)
        //           → IOSGraphics.setContinuousRendering()
        //           → view.setPaused(!isContinuous)                               ← UIKit
        //   • All other ObjC calls in Forge happen on the main thread:
        //       Foundation.log/NSLog, NSBundle, UIScreen, UIApplication, UIPasteboard,
        //       IOSFiles static init, computeBounds(), lifecycle callbacks.
        //   • Foundation.log() (NSLog) is thread-safe per Apple documentation. ✓
        //   • setupAccelerometer() and setupCompass() are overridden to no-ops below. ✓
        //
        // General fix: SafeIOSGraphics (see top of this file) overrides both
        // requestRendering() and setContinuousRendering() to dispatch to the main
        // GCD queue when called from a background thread.  This makes ALL
        // UIKit-touching calls through IOSGraphics safe regardless of calling thread.
        //
        // Defence-in-depth fix: Forge.create() calls startContinuousRendering() before
        // the background DB-load thread starts, keeping isContinuous=true so that
        // requestRendering() never reaches the viewController.setPaused() path at all
        // during loading.  During normal gameplay the continuous-rendering count is
        // always ≥ 1 (screens call startContinuousRendering in onActivate).
        //
        // Audio re-enable: ObjectAL's audio-init thread calls postRunnable → requestRendering
        // to sync with the GL loop.  SafeIOSGraphics handles this safely now.  Re-enable
        // audio once the app is confirmed to launch stably on a physical device.
        config.useAudio = false;
        boolean isLandscape = false;
        nslog("createApplication: calling Forge.getApp()");
        // Detect physical memory so Forge can scale the card-texture cache size down
        // for constrained iOS devices.  iOS enforces a per-process active-memory limit
        // of roughly 50% of physical RAM (≈ 2 GB on a 4 GB iPhone SE 3rd gen); exceeding
        // it causes an instant jetsam SIGKILL with no warning.
        // NSProcessInfo.getSharedProcessInfo().getPhysicalMemory() returns bytes as a long.
        int iosPhysicalRAMMB = 0;
        try {
            long physicalBytes = NSProcessInfo.getSharedProcessInfo().getPhysicalMemory();
            // physicalBytes / (1024² ) fits in an int for any plausible device (max ~2 TB = 2M MB,
            // well below Integer.MAX_VALUE ≈ 2.1G MB), but clamp defensively.
            iosPhysicalRAMMB = (int) Math.min(physicalBytes / (1024L * 1024L), Integer.MAX_VALUE);
            nslog("createApplication: physicalMemory=" + physicalBytes + " bytes (" + iosPhysicalRAMMB + " MB)");
        } catch (Throwable t) {
            nslog("createApplication: physicalMemory detection failed: " + t);
        }
        final ApplicationListener app = Forge.getApp(null, new IOSClipboard(), new IOSAdapter(assetsDir), assetsDir, false, !isLandscape, iosPhysicalRAMMB, false, 0);
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

            @Override
            protected IOSGraphics createGraphics() {
                // Use SafeIOSGraphics to ensure requestRendering() dispatches
                // viewController.setPaused() to the main thread when called from a
                // background thread (robovmx direct-IMP requirement).
                return new SafeIOSGraphics(this, config, (IOSInput) getInput(), config.useGL30);
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
