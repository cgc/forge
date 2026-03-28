package forge.gui.error;

/**
 * Web-target stub for {@code forge.gui.error.BugReporter}.
 *
 * <p>The real {@code BugReporter} declares several {@code public static final String}
 * fields that are initialized by calling
 * {@code Localizer.getInstance().getMessage(...)} at class-load time:
 *
 * <pre>
 *   public static final String REPORT  = Localizer.getInstance().getMessage("lblReport");
 *   public static final String SAVE    = Localizer.getInstance().getMessage("lblSave");
 *   ...
 * </pre>
 *
 * <p>Those static fields are evaluated the first time any code references the
 * {@code BugReporter} class.  On the web target that first reference is
 * {@code BugReporter.reportException()} called from
 * {@code ExceptionHandler.uncaughtException()} – i.e. during early startup,
 * <em>before</em> {@code FModel.initialize()} has run.
 * {@code FModel.initialize()} is the call that populates
 * {@code Localizer.resourceBundle}; until it completes the bundle is
 * {@code null}.  Calling {@code null.getString(key)} in TeaVM's compiled
 * JavaScript becomes {@code null.$handleGetObject(key)}, which throws:
 *
 * <pre>
 *   TypeError: Cannot read properties of null (reading '$handleGetObject')
 * </pre>
 *
 * <p>On the web target there is no interactive bug-report dialog, no writable
 * file system to save crash logs, and no Sentry integration.  This stub
 * replaces the UI-heavy implementation with lightweight {@code System.err}
 * logging, while keeping the same public API so the rest of the code base
 * compiles without modification.
 */
public final class BugReporter {

    // Plain string literals – no Localizer/ResourceBundle calls at class-load time.
    public static final String REPORT  = "Report";
    public static final String SAVE    = "Save";
    public static final String DISCARD = "Discard";
    public static final String EXIT    = "Exit";
    public static final String SENTRY  = "Auto-submit bug reports";

    private BugReporter() { }

    public static void reportException(final Throwable ex) {
        reportException(ex, (String) null);
    }

    public static void reportException(final Throwable ex, final String message) {
        if (ex == null) {
            return;
        }
        if (message != null && !message.isEmpty()) {
            System.err.println(message);
        }
        ex.printStackTrace();
    }

    public static void reportException(final Throwable ex, final String format,
                                       final Object... args) {
        reportException(ex, String.format(format, args));
    }

    public static void reportBug(final String details) {
        System.err.println("Bug report: " + details);
    }

    public static void saveToFile(final String error) {
        // No-op: file-system access is not available in the browser.
    }

    public static boolean isSentryEnabled() {
        return false;
    }

    public static void sendSentry() {
        // No-op: Sentry integration is not available on the web target.
    }
}
