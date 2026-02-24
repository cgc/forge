package com.badlogic.gdx.backends.iosrobovm.custom;

import org.robovm.apple.foundation.NSObject;
import org.robovm.rt.bro.annotation.Library;
import org.robovm.rt.bro.ptr.Ptr;
import org.robovm.objc.annotation.NativeClass;

/**
 * Stub that shadows the gdx-backend-robovm UIAccelerometer binding.
 *
 * The original class has {@code @Library("UIKit")}, {@code @NativeClass}, AND a
 * static initializer that calls {@code ObjCRuntime.bind(UIAccelerometer.class)}.
 *
 * <p>Why both {@code @NativeClass} and {@code @Library} are required here:<br>
 * Without {@code @NativeClass}, RoboVM's AOT compiler treats any {@code NSObject}
 * subclass as a <em>new custom ObjC class</em> and emits code to register it with
 * the ObjC runtime at process startup — before any Java code runs.  UIKit already
 * defines an ObjC class named {@code UIAccelerometer}; a duplicate registration is
 * a fatal ObjC runtime error that kills the process before {@code main()} executes
 * (explaining why even {@code System.err.println} never appears in device logs).
 * {@code @NativeClass} tells RoboVM this is a binding for an <em>existing</em> ObjC
 * class, suppressing new-class registration entirely.
 *
 * <p>Why {@code ObjCRuntime.bind()} is intentionally omitted from the static
 * initializer:<br>
 * {@code ObjCRuntime.bind()} sends the first ObjC message to {@code UIAccelerometer},
 * which triggers its {@code +initialize} method.  On iOS 14+, {@code +initialize}
 * creates an internal {@code CMMotionManager} instance that reads
 * {@code /private/var/Managed Preferences/mobile/com.apple.CoreMotion.plist} and
 * logs a noisy permission warning on supervised/MDM-enrolled devices.  By omitting
 * the bind call, {@code +initialize} is never triggered.  Since Forge sets
 * {@code config.useAccelerometer = false} and the {@code createInput()} override
 * makes {@code setupAccelerometer()} a no-op, no native UIAccelerometer method is
 * ever called at runtime, so the missing eager binding has no functional effect.
 */
@Library("UIKit")
@NativeClass
public class UIAccelerometer extends NSObject {

    public static class UIAccelerometerPtr extends Ptr<UIAccelerometer, UIAccelerometerPtr> {}

    // No static { ObjCRuntime.bind(...) } — that is the entire point of this stub.

    public UIAccelerometer() {}

    protected UIAccelerometer(SkipInit skipInit) {
        super(skipInit);
    }

    public double getUpdateInterval() { return 0; }

    public void setUpdateInterval(double v) {}

    public UIAccelerometerDelegate getDelegate() { return null; }

    public void setDelegate(UIAccelerometerDelegate v) {}

    public static UIAccelerometer getSharedAccelerometer() {
        throw new UnsupportedOperationException(
                "UIAccelerometer is disabled; useAccelerometer must be false");
    }
}
