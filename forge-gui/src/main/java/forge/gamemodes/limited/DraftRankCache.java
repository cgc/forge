package forge.gamemodes.limited;

import java.util.HashMap;
import java.util.Map;

/**
 * DraftRankCache
 *
 * <p>Ranking data is loaded <em>lazily per-edition</em>.  Previously the
 * cache loaded all 183 set-ranking files at once the first time any ranking
 * was requested.  That caused a ~20 MB heap spike during the very first AI
 * pick of a booster draft — the only mode where AI evaluation happens — and
 * the data was never freed even between draft sessions.
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
     * Return the relative draft ranking for {@code name} in {@code edition},
     * loading the per-edition .rnk file lazily if not yet loaded.
     */
    public static synchronized Double getRanking(String name, String edition) {
        if (!editionRankings.containsKey(edition)) {
            String relPath = "rankings/" + edition.toLowerCase() + ".rnk";
            ReadDraftRankings r = new ReadDraftRankings(relPath);
            // Store null if the file was missing/empty so we don't probe again.
            editionRankings.put(edition, r.hasRankingsForEdition(edition) ? r : null);
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
        editionRankings.clear();
        customRankings = null;
        customRankingsFileName = "";
    }
}
