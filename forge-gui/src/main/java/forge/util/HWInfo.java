package forge.util;

import io.sentry.protocol.Device;
import io.sentry.protocol.OperatingSystem;

public final class HWInfo {
    private final Device device;
    private final OperatingSystem os;
    private final boolean getChipset;

    public HWInfo(Device device, OperatingSystem os, boolean getChipset) {
        this.device = device;
        this.os = os;
        this.getChipset = getChipset;
    }

    public Device device() { return device; }
    public OperatingSystem os() { return os; }
    public boolean getChipset() { return getChipset; }
}
