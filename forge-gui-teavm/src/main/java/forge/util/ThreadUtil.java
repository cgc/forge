package forge.util;

import java.util.concurrent.Callable;

/**
 * Web-target stub for {@code forge.util.ThreadUtil}.
 *
 * <p>The real {@code ThreadUtil} declares static fields of type
 * {@code java.util.concurrent.ExecutorService}, {@code ScheduledExecutorService},
 * and {@code Executors} – classes absent from TeaVM's JavaScript class library.
 * The static initialiser ({@code <clinit>}) therefore fails as soon as any code
 * references {@code ThreadUtil}, producing a 0-byte {@code app.js}.
 *
 * <p>On the JavaScript target the VM is single-threaded; all "background thread"
 * operations either run synchronously or are posted to the GDX render loop.
 * This stub replaces every threading primitive with a safe no-op or direct call.
 */
public class ThreadUtil {

    private ThreadUtil() { }

    public static boolean isMultiCoreSystem() {
        return false;
    }

    /**
     * On the web target there is no dedicated game thread.
     * Always returns {@code false} so that {@code isGuiThread()} returns {@code true}.
     */
    public static boolean isGameThread() {
        return false;
    }

    /** Runs {@code toRun} synchronously (JS is single-threaded). */
    public static void invokeInGameThread(Runnable toRun) {
        toRun.run();
    }

    /**
     * Schedules {@code inputUpdater} after a delay.
     * On the web target the delay is ignored and the task is run immediately
     * (a proper GDX Timer integration can be added later).
     * Returns {@code null} because {@code ScheduledFuture} is not available.
     */
    public static Object delay(int milliseconds, Runnable inputUpdater) {
        inputUpdater.run();
        return null;
    }

    /**
     * Executes {@code task} synchronously and returns the result.
     * The timeout is ignored – JavaScript cannot abort synchronous execution.
     */
    public static <T> T executeWithTimeout(Callable<T> task, int milliseconds) {
        try {
            return task.call();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Executes {@code task} synchronously and returns the result.
     * The millis limit is ignored on the web target.
     */
    public static <T> T limit(Callable<T> task, long millis) {
        try {
            return task.call();
        } catch (Exception e) {
            return null;
        }
    }

    /** No-op on the web target (no thread pool to refresh). */
    public static void refreshServicePool() {
    }
}
