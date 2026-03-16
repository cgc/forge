package forge.teavm;

import org.apache.commons.lang3.tuple.Pair;

import java.util.Collections;
import java.util.List;

/**
 * Web-target stub helpers referenced by {@link ForgeTeaVMTransformer}.
 *
 * <p>These static methods provide safe, no-op or default-returning replacements
 * for code paths in the Forge core that call JVM APIs unavailable in TeaVM's
 * JavaScript class library (e.g. {@code File.toPath()},
 * {@code System.getenv()}).  The transformer patches the bytecode of those
 * Forge methods <em>before</em> TeaVM's dependency analysis, so the problematic
 * JVM API references are never seen by the analyser.
 *
 * <p>All methods in this class must be genuinely available to TeaVM
 * (i.e. only use classes that are in TeaVM's JS classlib or are otherwise
 * stubbed in this module).
 */
public final class ForgeWebStubs {

    private ForgeWebStubs() { }

    /**
     * Web replacement for {@code ForgeProfileProperties.getDefaultDirs()}.
     *
     * <p>Returns the mobile-style pair
     * {@code (ASSETS_DIR + "data/", ASSETS_DIR + "cache/")}.
     * At runtime the real {@code getDefaultDirs()} would take exactly this
     * early-exit path because {@code GuiMobile.isRunningOnDesktop()} returns
     * {@code false} for the web target; however TeaVM's static analyser cannot
     * see through the runtime guard and therefore reports the
     * Windows/Mac/Linux branches (which call {@code System.getenv()}) as
     * "method not found" errors.  This replacement pre-computes the correct
     * value so the problematic branches are never in the compiled bytecode.
     *
     * @param assetsDir value of {@code ForgeConstants.ASSETS_DIR} at the call
     *                  site – passed in so this class does not need to depend
     *                  on {@code ForgeConstants} directly.
     */
    public static Pair<String, String> webDefaultDirs(String assetsDir) {
        String sep = "/";
        return Pair.of(assetsDir + "data" + sep, assetsDir + "cache" + sep);
    }

    /**
     * Web replacement for {@code FileUtil.readAllLines(File, boolean)}.
     *
     * <p>Returns an empty list.  On the web target there is no local
     * filesystem, so any file read would fail at runtime anyway.  The Forge
     * preferences system handles missing/empty files gracefully by using
     * built-in defaults.
     */
    public static List<String> emptyReadAllLines() {
        return Collections.emptyList();
    }
}
