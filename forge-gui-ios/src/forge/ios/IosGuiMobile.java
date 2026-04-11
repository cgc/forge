package forge.ios;

import forge.GuiMobile;
import forge.gui.GuiBase;

import java.util.concurrent.Semaphore;

/**
 * iOS-specific {@link GuiMobile} subclass that throttles work posted to the
 * Event Dispatch Thread (EDT).
 *
 * <h3>Motivation</h3>
 * During {@code FSkinFont.preloadAll()} a background thread generates one
 * FreeType font atlas per size and posts each atlas upload as a Runnable to
 * the EDT via {@link #invokeInEdtLater}.  Without back-pressure all 65 atlas
 * Runnables can be queued before the EDT processes any of them, keeping up to
 * 65 {@code PixmapPacker} instances alive simultaneously and causing ~181 MB
 * of transient heap/GPU pressure.
 *
 * <h3>Mechanism</h3>
 * {@link #invokeInEdtLater} acquires one permit from a {@link Semaphore}
 * before posting work to the EDT, and the posted Runnable releases the permit
 * in its {@code finally} block after it completes.  With {@code maxConcurrent=2}
 * at most two atlas uploads are queued or running at any moment, giving
 * CPU/GPU overlap while bounding peak memory to ~2–3 atlas pages.
 *
 * <h3>Thread safety</h3>
 * The acquire is always done on the caller's thread (typically a background
 * thread).  If called from the EDT itself (e.g. {@code InputLockUI}), the
 * throttle is bypassed to prevent deadlock: the EDT cannot wait for itself to
 * release a permit.
 */
public class IosGuiMobile extends GuiMobile {

    private final Semaphore semaphore;

    /**
     * @param assetsDir    path to the app-bundle assets root (passed through to
     *                     {@link GuiMobile})
     * @param maxConcurrent maximum number of EDT-bound tasks that may be queued
     *                     or running simultaneously before callers block
     */
    public IosGuiMobile(final String assetsDir, final int maxConcurrent) {
        super(assetsDir);
        semaphore = new Semaphore(maxConcurrent);
    }

    /**
     * Posts {@code proc} to the EDT, acquiring a throttle permit first.
     *
     * <p>If the caller is already on the EDT the permit is <em>not</em> acquired
     * (to avoid deadlock) and {@code proc} is posted without throttling.
     *
     * <p>The permit is released inside a {@code finally} block that wraps
     * {@code proc.run()} on the EDT, so permit lifetime exactly matches the
     * lifetime of each queued/running task.
     */
    @Override
    public void invokeInEdtLater(final Runnable proc) {
        if (GuiBase.getInterface().isGuiThread()) {
            // Already on EDT — skip throttle to avoid deadlock.
            super.invokeInEdtLater(proc);
            return;
        }
        semaphore.acquireUninterruptibly();
        super.invokeInEdtLater(() -> {
            try {
                proc.run();
            } finally {
                semaphore.release();
            }
        });
    }
}
