package forge.gamemodes.limited;

import java.util.HashMap;
import java.util.Map;

/**
 * DraftRankCache
 *
 * <p>Ranking data is now loaded <em>lazily per-edition</em>.  Previously the
 * cache loaded all 183 set-ranking files at once the first time any ranking
 * was requested.  That caused a ~20 MB heap spike during the very first AI
 * pick of a booster draft — the only mode where AI evaluation happens — and
 * the data was never freed even between draft sessions.  On iOS, where the
 * process memory budget is ~2 GB, this spike was one of the contributors to
 * the jetsam kill observed exclusively during booster drafts (not sealed,
 * which has no AI picker).
 *
 * <p>With per-edition lazy loading, a typical single-set booster draft loads
 * exactly one {@code .rnk} file (~0.1 MB) instead of all 183 (~20 MB combined).
 * Each edition's data is loaded on the first {@code getRanking()} call for
 * that edition and cached for the lifetime of the draft session.
 * Call {@link #clear()} when the draft session ends to release all loaded
 * ranking data and allow the GC to reclaim the heap.
 */
public class DraftRankCache {

    /**
     * Per-edition rankings loaded on demand.
     * A null value in this map means the .rnk file for that edition does not
     * exist (or is empty), so a null is stored to avoid repeated file-system
     * probes.
     */
    private static final Map<String, ReadDraftRankings> editionRankings = new HashMap<>();

    private static ReadDraftRankings customRankings = null;
    private static String customRankingsFileName = "";

    private DraftRankCache() {}

    /**
     * Prints current Java heap usage to stdout with a context tag.
     * On iOS the output is captured by os_log and is visible in Console.app.
     * Example line: {@code [Forge/Draft-Mem] draft-start: used=142MB total=256MB}
     *
     * <p>Call this at key draft lifecycle points to build a quantitative picture
     * of memory use during a booster draft session.  Sealed and constructed
     * modes do not invoke the AI draft-pick path, so any memory seen here that
     * is absent from those modes can be attributed to the draft AI evaluation.
     */
    public static void logHeap(String tag) {
        Runtime rt = Runtime.getRuntime();
        long usedMB  = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long totalMB = rt.totalMemory() >> 20;
        System.out.println("[Forge/Draft-Mem] " + tag
                + ": used=" + usedMB + "MB total=" + totalMB + "MB"
                + " editions=" + editionRankings.size());
    }

    /**
     * Return the relative draft ranking for {@code name} in {@code edition},
     * loading the per-edition .rnk file lazily if not yet loaded.
     */
    public static synchronized Double getRanking(String name, String edition) {
        if (!editionRankings.containsKey(edition)) {
            // Load only the .rnk file for the requested edition.
            // "rankings/" + lowercase edition code is the path relative to
            // DRAFT_DIR that the ReadDraftRankings(String) constructor accepts.
            String relPath = "rankings/" + edition.toLowerCase() + ".rnk";
            logHeap("load-rankings-" + edition);
            ReadDraftRankings r = new ReadDraftRankings(relPath);
            // Store null if the file was missing/empty so we don't probe again.
            editionRankings.put(edition, r.hasRankingsForEdition(edition) ? r : null);
            logHeap("loaded-rankings-" + edition);
        }
        ReadDraftRankings r = editionRankings.get(edition);
        return r != null ? r.getRanking(name, edition) : null;
    }

    public static synchronized Double getCustomRanking(String customRankingsSource, String name) {
        if (customRankings == null || !customRankingsFileName.equals(customRankingsSource)) {
            customRankingsFileName = customRankingsSource;
            customRankings = new ReadDraftRankings(customRankingsFileName);
        }
        return customRankings.getRanking(name, "CUSTOM");
    }

    /**
     * Release all cached ranking data.  Call when a booster draft session
     * completes or is abandoned so the GC can reclaim the heap used by the
     * per-edition ranking maps.
     *
     * <p>Must only be called when no draft AI evaluation is in progress
     * (i.e., after the last background pick thread has completed).
     */
    public static synchronized void clear() {
        logHeap("clear");
        editionRankings.clear();
        customRankings = null;
        customRankingsFileName = "";
    }
}
