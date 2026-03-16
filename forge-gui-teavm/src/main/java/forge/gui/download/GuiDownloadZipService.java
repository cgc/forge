package forge.gui.download;

import forge.gui.interfaces.IProgressBar;

import java.util.Collections;
import java.util.Map;

/**
 * Web-target stub for {@code forge.gui.download.GuiDownloadZipService}.
 *
 * <p>On the web target, zip-based asset downloads are not performed via a Java
 * socket connection.  This no-op stub satisfies the call sites in
 * {@code AssetsDownloader.checkForUpdates()} so the class can be loaded
 * without triggering missing-class errors in TeaVM.
 */
public class GuiDownloadZipService extends GuiDownloadService {

    public GuiDownloadZipService(String name0, String desc0, String sourceUrl0,
            String destFolder0, String deleteFolder0, IProgressBar progressBar0) {
    }

    public GuiDownloadZipService(String name0, String desc0, String sourceUrl0,
            String destFolder0, String deleteFolder0, IProgressBar progressBar0,
            boolean allowDeletion0) {
    }

    @Override
    public String getTitle() {
        return "";
    }

    @Override
    protected Map<String, String> getNeededFiles() {
        return Collections.emptyMap();
    }

    @Override
    public void run() { }

    public void downloadAndUnzip() { }

    /** Returns {@code null} — no network downloads on the web target. */
    public String download(final String filename) {
        return null;
    }
}
