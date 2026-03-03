package forge.interfaces;

import org.apache.commons.lang3.tuple.Pair;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;

public interface IDeviceAdapter {
    boolean isConnectedToInternet();
    boolean isConnectedToWifi();
    boolean isTablet();
    String getDownloadsDir();
    String getVersionString();
    String getLatestChanges(String commitsAtom, Date buildDateOriginal, Date maxDate);
    String getReleaseTag(String releaseAtom);
    boolean openFile(String filename);
    void setLandscapeMode(boolean landscapeMode);
    void preventSystemSleep(boolean preventSleep);
    void restart();
    void exit();
    void closeSplashScreen();
    void convertToJPEG(InputStream input, OutputStream output) throws IOException;
    Pair<Integer, Integer> getRealScreenSize(boolean real);
    ArrayList<String> getGamepads();
    UpnpServiceConfiguration getUpnpPlatformService();
    boolean needFileAccess();
    void requestFileAcces();

    default boolean isSupportedAudioFormat(File file) {
        // Set.of() and .stream() are desugared at build time by StreamDesugar
        // (Pattern 38 → StreamUtil.setOf, Pattern 1 → StreamUtil.stream).
        // The set is created inline here rather than in a static interface field to
        // avoid relying on the interface <clinit>, which RoboVM may not execute
        // reliably for non-constant static fields.
        String path = file.getPath().toLowerCase();
        return Set.of(".wav", ".mp3", ".ogg").stream().anyMatch(path::endsWith);
    }
}
