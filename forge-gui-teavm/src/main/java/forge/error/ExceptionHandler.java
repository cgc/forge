package forge.error;

import forge.gui.error.BugReporter;

/**
 * TeaVM browser stub for {@link forge.error.ExceptionHandler}.
 *
 * <p>The real {@code ExceptionHandler} does three things that are unsafe or
 * unsupported in the browser:
 * <ol>
 *   <li>{@code registerErrorHandling()} creates a log file via
 *       {@code FileOutputStream} and wraps {@code System.out}/{@code System.err}
 *       in a {@code MultiplexOutputStream}.  The virtual-filesystem operations
 *       work in TeaVM but the {@code System.setOut/setErr} gymnastics can
 *       interfere with console output.</li>
 *   <li>The static initialiser calls
 *       {@code System.setProperty("sun.awt.exception.handler", …)} which has
 *       no effect in a browser but also causes no harm.</li>
 *   <li>The uncaught-exception handler delegates to
 *       {@link BugReporter#reportException} which, in the real class, tries to
 *       show a Swing dialog.</li>
 * </ol>
 *
 * <p>This stub replaces the whole class so that:
 * <ul>
 *   <li>{@link #registerErrorHandling()} is a no-op – the browser already
 *       surfaces errors via {@code console.error}.</li>
 *   <li>{@link #uncaughtException} still delegates to
 *       {@link BugReporter#reportException} which in the web stub prints to
 *       {@code System.err}.</li>
 * </ul>
 */
public class ExceptionHandler implements Thread.UncaughtExceptionHandler {

    static {
        Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler());
    }

    /** No-op: no log file or stream multiplexing needed in the browser. */
    public static void registerErrorHandling() {
        // no-op for TeaVM browser target
    }

    /** No-op: nothing to clean up. */
    public static void unregisterErrorHandling() {
        // no-op for TeaVM browser target
    }

    @Override
    public void uncaughtException(Thread t, Throwable ex) {
        BugReporter.reportException(ex);
    }
}
