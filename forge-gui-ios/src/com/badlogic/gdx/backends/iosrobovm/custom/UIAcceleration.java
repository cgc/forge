package com.badlogic.gdx.backends.iosrobovm.custom;

import org.robovm.apple.foundation.NSObject;
import org.robovm.rt.bro.annotation.Library;
import org.robovm.rt.bro.ptr.Ptr;
import org.robovm.objc.annotation.NativeClass;

/**
 * Stub that shadows the gdx-backend-robovm UIAcceleration binding.
 *
 * <p>The same {@code @NativeClass} / {@code @Library} requirement applies here as
 * for {@link UIAccelerometer}: without {@code @NativeClass}, RoboVM would emit code
 * to register a <em>new</em> ObjC class named {@code UIAcceleration} at startup,
 * colliding with UIKit's existing class of that name and causing a fatal abort.
 *
 * <p>{@code ObjCRuntime.bind()} is intentionally omitted for the same reason: it
 * is not needed because no native methods on this class are ever called (the
 * accelerometer is disabled and {@code setupAccelerometer()} is overridden to a
 * no-op), and omitting it avoids any indirect CoreMotion activation.
 */
@Library("UIKit")
@NativeClass
public class UIAcceleration extends NSObject {

    public static class UIAccelerationPtr extends Ptr<UIAcceleration, UIAccelerationPtr> {}

    // No static { ObjCRuntime.bind(...) }

    public UIAcceleration() {}

    protected UIAcceleration(SkipInit skipInit) {
        super(skipInit);
    }

    public double getTimestamp() { return 0; }

    public double getX() { return 0; }

    public double getY() { return 0; }

    public double getZ() { return 0; }
}
