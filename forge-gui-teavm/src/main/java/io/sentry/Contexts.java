package io.sentry;

import io.sentry.protocol.Device;
import io.sentry.protocol.OperatingSystem;

/**
 * Stub replacement for {@code io.sentry.Contexts}.
 *
 * <p>Returned by {@link IScope#getContexts()} and used in the
 * {@code Sentry.configureScope} lambda in {@code forge/Forge.java}.
 */
public final class Contexts {

    public void setDevice(Device device) { }

    public void setOperatingSystem(OperatingSystem operatingSystem) { }
}
