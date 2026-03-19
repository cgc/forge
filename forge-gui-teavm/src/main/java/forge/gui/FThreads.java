package forge.gui;

/**
 * Web-target stub for {@code forge.gui.FThreads}.
 *
 * <p>The real {@code FThreads.delayInEDT} calls
 * {@code ThreadUtil.delay(int, Runnable)} which returns
 * {@code java.util.concurrent.ScheduledFuture} – absent from TeaVM's JS
 * class library.  This stub replaces the call with a direct invocation of
 * the runnable (JS is single-threaded; delays are a no-op).
 */
public class FThreads {
    private FThreads() { }

    public static void assertExecutedByEdt(final boolean mustBeEDT) {
    }

    public static void invokeInEdtLater(final Runnable proc) {
        GuiBase.getInterface().invokeInEdtLater(proc);
    }

    public static void invokeInEdtNowOrLater(final Runnable proc) {
        if (isGuiThread()) {
            GuiBase.getInterface().invokeInEdtNow(proc);
        } else {
            GuiBase.getInterface().invokeInEdtLater(proc);
        }
    }

    public static void invokeInEdtAndWait(final Runnable proc) {
        GuiBase.getInterface().invokeInEdtAndWait(proc);
    }

    private static int backgroundThreadCount;
    public static void invokeInBackgroundThread(final Runnable proc) {
        new Thread(proc, "Game BT" + backgroundThreadCount).start();
        backgroundThreadCount++;
    }

    public static boolean isGuiThread() {
        return GuiBase.getInterface().isGuiThread();
    }

    /** On the web target, runs {@code inputUpdater} immediately (no true delay). */
    public static void delayInEDT(final int milliseconds, final Runnable inputUpdater) {
        invokeInEdtNowOrLater(inputUpdater);
    }

    public static String debugGetCurrThreadId() {
        return isGuiThread() ? "EDT" : Thread.currentThread().getName();
    }

    public static String debugGetStackTraceItem(final int depth, final boolean shorter) {
        final StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        String lastItem = trace[depth].toString();
        if (shorter) {
            int lastPeriod = lastItem.lastIndexOf('.');
            lastPeriod = lastItem.lastIndexOf('.', lastPeriod - 1);
            lastPeriod = lastItem.lastIndexOf('.', lastPeriod - 1);
            lastItem = lastItem.substring(lastPeriod + 1);
            return String.format("%s > from %s", debugGetCurrThreadId(), lastItem);
        }
        return String.format("%s > %s called from %s", debugGetCurrThreadId(),
                trace[2].getClassName() + "." + trace[2].getMethodName(), lastItem);
    }

    public static String debugGetStackTraceItem(final int depth) {
        return debugGetStackTraceItem(depth, false);
    }
}
