package forge.util;

import java.util.Date;

/**
 * Web-target stub for {@code forge.util.BuildInfo}.
 *
 * <p>The real {@code BuildInfo.getVersionString()} calls
 * {@code Class.getPackage().getImplementationVersion()} – a method absent from
 * TeaVM's JavaScript class library.  This minimal stub replaces it with a
 * hard-coded "web" version string.
 */
public class BuildInfo {

    private BuildInfo() { }

    public static String getVersionString() {
        return "web";
    }

    public static boolean isDevelopmentVersion() {
        return true;
    }

    public static Date getTimestamp() {
        return null;
    }

    public static boolean verifyTimestamp(Date updateTimestamp) {
        return false;
    }

    public static String getUserAgent() {
        return "Forge/web";
    }
}
