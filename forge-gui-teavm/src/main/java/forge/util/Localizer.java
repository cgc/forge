package forge.util;

import java.util.ArrayList;
import java.util.List;

/**
 * TeaVM browser stub for {@link forge.util.Localizer}.
 *
 * <p>The real {@code Localizer} uses {@code java.net.URLClassLoader} to load
 * {@code .properties} resource bundles from the filesystem.
 * {@code URLClassLoader} is not available in TeaVM's JS class library, so
 * instantiating it throws {@code NoClassDefFoundError: java.net.URLClassLoader}
 * at runtime in the browser.
 *
 * <p>This stub replaces the entire class for the TeaVM target:
 * <ul>
 *   <li>{@link #initialize} and {@link #setLanguage} are no-ops – there is no
 *       filesystem to load from in a browser.</li>
 *   <li>{@link #getMessage} returns the raw key as a fallback so the UI still
 *       renders (with English key names) rather than crashing.</li>
 *   <li>All other public surface area is preserved so call-sites compile.</li>
 * </ul>
 */
public class Localizer {

    private static Localizer instance;

    private List<LocalizationChangeObserver> observers = new ArrayList<>();
    private boolean english = false;

    public static Localizer getInstance() {
        if (instance == null) {
            synchronized (Localizer.class) {
                if (instance == null) {
                    instance = new Localizer();
                }
            }
        }
        return instance;
    }

    private Localizer() {
    }

    public void setEnglish(boolean value) {
        english = value;
    }

    /** No-op: no filesystem resource bundles in the browser. */
    public void initialize(String localeID, String languagesDirectory) {
        // no-op for TeaVM browser target
    }

    /** No-op: URLClassLoader is unavailable in TeaVM's JS classlib. */
    public void setLanguage(String languageRegionID, String languagesDirectory) {
        // no-op for TeaVM browser target
    }

    /**
     * Returns the translation key as a plain string.  This keeps the UI
     * functional (with key-name labels) while the full asset set / a
     * separate localisation mechanism is wired up.
     */
    public String getMessage(String key, Object... messageArguments) {
        return key;
    }

    public String getMessage(boolean forcedEnglish, String key, Object... messageArguments) {
        return key;
    }

    public String getEnglishMessage(String key, Object... messageArguments) {
        return key;
    }

    public String getMessageorUseDefault(String key, String defaultValue, Object... messageArguments) {
        return defaultValue != null ? defaultValue : key;
    }

    public List<Language> getLanguages() {
        return null;
    }

    public void registerObserver(LocalizationChangeObserver observer) {
        observers.add(observer);
    }

    public static class Language {
        public String languageName;
        public String languageID;
    }
}
