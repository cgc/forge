package forge.gamemodes.limited;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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

    /**
     * Optional provider for the iOS process physical footprint in MB.
     *
     * <p>Set at startup by {@code Main} (forge-gui-ios) so that
     * {@link #logHeap(String)} can report the actual OS-level memory that iOS
     * jetsam monitors, in addition to the Java heap metrics.
     * {@code null} on all non-iOS platforms; {@link #logHeap} silently skips
     * the {@code phys=} line when this is {@code null}.
     *
     * <p>Populated via a method reference to
     * {@code MachMemInfo::getPhysicalFootprintMB} which reads
     * {@code task_vm_info_data_t.phys_footprint} from the Mach kernel.
     */
    public static volatile LongSupplier physicalFootprintMBSupplier = null;

    /**
     * Optional provider for an extended {@code task_vm_info_data_t} summary
     * line, populated on iOS only.
     *
     * <p>Set at startup by {@code Main} (forge-gui-ios) via
     * {@code MachMemInfo::getVmInfoLine}.  When non-null, {@link #logHeap}
     * appends the returned string on a separate log line tagged with
     * {@code [Forge/Mem-VM]}.  The string contains key fields in MB:
     * {@code phys}, {@code internal}, {@code compressed}, {@code phys_peak},
     * {@code gfx} (GPU/Metal texture footprint), and {@code gfx_nofp}.
     *
     * <p>{@code null} on all non-iOS platforms; {@link #logHeap} silently
     * skips the vm-info line when this is {@code null}.
     */
    public static volatile Supplier<String> vmInfoLineSupplier = null;

    private DraftRankCache() {}

    /**
     * Prints current Java heap usage to stdout with a context tag, and (on iOS)
     * the Mach physical footprint and extended vm_info that jetsam actually monitors.
     * On iOS the output is captured by os_log and is visible in Console.app.
     *
     * <p>Example output on iOS:
     * <pre>
     * [Forge/Heap] post-card-load: used=142MB total=256MB max=8796093022207MB editions=0
     * [Forge/Heap] post-card-load: phys=1140MB
     * [Forge/Mem-VM] post-card-load: phys=1140MB internal=890MB compressed=120MB phys_peak=1155MB gfx=230MB gfx_nofp=0MB
     * </pre>
     *
     * <p>The {@code max=} field is {@link Runtime#maxMemory()} — on Boehm GC
     * (robovmx) this is {@code Long.MAX_VALUE} (no configured limit), which
     * explains the large constant value in iOS logs.  It is included to keep
     * the output consistent with standard JVM diagnostics.
     *
     * <p>Call this at key lifecycle points (startup phases, draft events) to
     * build a quantitative picture of memory use.  The {@code phys=} line
     * reports total process resident memory (Java heap + GPU textures + native
     * code + other), which is what iOS jetsam compares against the per-device
     * limit.  The {@code gfx=} field in the vm-info line isolates GPU/Metal
     * texture memory — the dominant non-Java consumer in Forge.
     */
    public static void logHeap(String tag) {
        Runtime rt = Runtime.getRuntime();
        long usedMB  = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long totalMB = rt.totalMemory() >> 20;
        long maxMB   = rt.maxMemory() >> 20;
        System.out.println("[Forge/Heap] " + tag
                + ": used=" + usedMB + "MB total=" + totalMB + "MB max=" + maxMB + "MB"
                + " editions=" + editionRankings.size());
        LongSupplier s = physicalFootprintMBSupplier;
        if (s != null) {
            System.out.println("[Forge/Heap] " + tag + ": phys=" + s.getAsLong() + "MB");
        }
        Supplier<String> vm = vmInfoLineSupplier;
        if (vm != null) {
            String line = vm.get();
            if (line != null) {
                System.out.println("[Forge/Mem-VM] " + tag + ": " + line);
            }
        }
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
