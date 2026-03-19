package io.sentry.protocol;

/**
 * Stub replacement for {@code io.sentry.protocol.Device}.
 *
 * <p>Used in {@link forge.util.HWInfo} (a Java record type) and the
 * {@code Sentry.configureScope} lambda in {@code forge/Forge.java}.  Since
 * the web launcher always passes {@code null} for {@code hwInfo}, the code
 * that populates this object is dead at runtime, but TeaVM still compiles the
 * whole class and needs the type to resolve.
 */
public final class Device {

    public String getName() { return null; }

    public String getCpuDescription() { return null; }

    public String getChipset() { return null; }
}
