package forge.gui.download;

import forge.gui.UiCommand;
import forge.gui.interfaces.IButton;
import forge.gui.interfaces.IProgressBar;
import forge.gui.interfaces.ITextField;

import java.util.Map;

/**
 * Web-target stub for {@code forge.gui.download.GuiDownloadService}.
 *
 * <p>The real class declares {@code public static final Proxy.Type[] TYPES =
 * Proxy.Type.values()} which runs in the static initialiser {@code <clinit>}.
 * {@code java.net.Proxy.Type} is absent from TeaVM's JavaScript class library,
 * so the class initialiser throws before any instance is created, producing a
 * 0-byte {@code app.js}.
 *
 * <p>On the web target, all asset downloads go through the browser's HTTP
 * mechanism rather than a Java {@link java.net.Proxy} socket.  This stub
 * replaces the whole class hierarchy with no-ops.
 */
public abstract class GuiDownloadService implements Runnable {

    /** Empty proxy-type array — no proxy configuration on the web target. */
    public static final Object[] TYPES = new Object[0];

    protected IProgressBar progressBar;
    protected boolean cancel;

    protected GuiDownloadService() { }

    public void initialize(ITextField txtAddress0, ITextField txtPort0,
            IProgressBar progressBar0, IButton btnStart0,
            UiCommand cmdClose0, Runnable onReadyToStart, Runnable onUpdate0) {
    }

    public void setType(int type0) { }

    public void setCancel(boolean cancel0) {
        cancel = cancel0;
    }

    @Override
    public void run() { }

    public abstract String getTitle();

    protected abstract Map<String, String> getNeededFiles();
}
