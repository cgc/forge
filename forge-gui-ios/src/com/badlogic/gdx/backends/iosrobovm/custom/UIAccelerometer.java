package com.badlogic.gdx.backends.iosrobovm.custom;

import org.robovm.apple.foundation.NSObject;
import org.robovm.rt.bro.ptr.Ptr;

/**
 * Stub that shadows the gdx-backend-robovm UIAccelerometer binding.
 *
 * The original class has a static initializer that calls
 * {@code ObjCRuntime.bind(UIAccelerometer.class)}.  On iOS 14+ that call
 * reaches into UIKit's UIAccelerometer +initialize, which creates an
 * internal CMMotionManager instance.  CMMotionManager initialization reads
 * {@code /private/var/Managed Preferences/mobile/com.apple.CoreMotion.plist}
 * and logs a noisy permission warning on supervised/MDM-enrolled devices.
 *
 * Because Forge disables the accelerometer entirely
 * ({@code config.useAccelerometer = false}) and overrides
 * {@code setupAccelerometer()} to be a no-op, none of the native methods
 * below are ever called at runtime.  The stub merely needs to satisfy the
 * compile-time references that {@code DefaultIOSInput} holds.
 */
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
