package com.badlogic.gdx.backends.iosrobovm.custom;

import org.robovm.apple.foundation.NSObject;
import org.robovm.rt.bro.ptr.Ptr;

/**
 * Stub that shadows the gdx-backend-robovm UIAcceleration binding.
 *
 * The original class has a static initializer that calls
 * {@code ObjCRuntime.bind(UIAcceleration.class)}, which registers the ObjC
 * class binding at process startup.  Although UIAcceleration itself does not
 * directly trigger CoreMotion, removing the bind() call removes one
 * additional ObjC class registration that is entirely unnecessary given that
 * the accelerometer is disabled.
 *
 * None of the methods below are ever called at runtime because
 * {@code setupAccelerometer()} is overridden to a no-op.
 */
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
