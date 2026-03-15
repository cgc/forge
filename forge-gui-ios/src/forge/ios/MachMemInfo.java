package forge.ios;

import org.robovm.rt.bro.Bro;
import org.robovm.rt.bro.annotation.Bridge;
import org.robovm.rt.bro.annotation.GlobalValue;
import org.robovm.rt.bro.annotation.Library;
import org.robovm.rt.bro.annotation.Pointer;
import sun.misc.Unsafe;

/**
 * Mach kernel bindings for reading the iOS process physical-footprint and
 * detailed VM accounting from {@code task_vm_info_data_t}.
 *
 * <p>Physical footprint ({@code task_vm_info_data_t.phys_footprint}) is the
 * metric iOS's jetsam daemon compares against the per-device resident-memory
 * limit when deciding whether to kill the process (the value reported in the
 * "exceeded mem limit: ActiveHard NNN MB (fatal)" log line).
 *
 * <p>Java's {@link Runtime#totalMemory()} and {@link Runtime#freeMemory()} only
 * cover the Boehm GC heap.  They are blind to GPU textures, native code, and
 * other non-Java allocations that can account for the majority of the total
 * process footprint in a graphics-heavy app like Forge.  libGDX's
 * {@code IOSApplication.getNativeHeap()} also returns only the Java heap
 * (it delegates to {@code getJavaHeap()}), so is equally uninformative.
 *
 * <p>Uses the Mach {@code task_info(mach_task_self(), TASK_VM_INFO, ...)}
 * system call which is available on all iOS versions.
 *
 * <p>Full {@code task_vm_info_data_t} layout on arm64 (all fields are uint64
 * except {@code region_count} and {@code page_size} which are int32):
 * <pre>
 *   ── TASK_VM_INFO_REV0 (rev0) ──────────────────────────────
 *   offset   0  virtual_size                            uint64
 *   offset   8  region_count                            int32
 *   offset  12  page_size                               int32
 *   offset  16  resident_size                           uint64
 *   offset  24  resident_size_peak                      uint64
 *   ── TASK_VM_INFO_REV1 additions ───────────────────────────
 *   offset  32  device                                  uint64
 *   offset  40  device_peak                             uint64
 *   offset  48  internal                                uint64  ← Java heap / anon pages
 *   offset  56  internal_peak                           uint64
 *   offset  64  external                                uint64
 *   offset  72  external_peak                           uint64
 *   offset  80  reusable                                uint64
 *   offset  88  reusable_peak                           uint64
 *   offset  96  purgeable_volatile_pmap                 uint64
 *   offset 104  purgeable_volatile_resident             uint64
 *   offset 112  purgeable_volatile_virtual              uint64
 *   offset 120  compressed                              uint64  ← swapped-out pages
 *   offset 128  compressed_peak                         uint64
 *   offset 136  compressed_lifetime                     uint64
 *   offset 144  phys_footprint                          uint64  ← jetsam metric
 *   TASK_VM_INFO_REV1_COUNT = 38 (38 × 4 = 152 bytes)
 *   ── TASK_VM_INFO_REV2 additions ───────────────────────────
 *   offset 152  min_address                             uint64
 *   offset 160  max_address                             uint64
 *   TASK_VM_INFO_REV2_COUNT = 42 (42 × 4 = 168 bytes)
 *   ── TASK_VM_INFO_REV3 additions ───────────────────────────
 *   offset 168  ledger_phys_footprint_peak              int64   ← peak jetsam footprint
 *   offset 176  ledger_purgeable_nonvolatile            int64
 *   offset 184  ledger_purgeable_novolatile_compressed  int64
 *   offset 192  ledger_purgeable_volatile               int64
 *   offset 200  ledger_purgeable_volatile_compressed    int64
 *   offset 208  ledger_tag_network_nonvolatile          int64
 *   offset 216  ledger_tag_network_nonvolatile_compressed int64
 *   offset 224  ledger_tag_network_volatile             int64
 *   offset 232  ledger_tag_network_volatile_compressed  int64
 *   offset 240  ledger_tag_media_footprint              int64
 *   offset 248  ledger_tag_media_footprint_compressed   int64
 *   offset 256  ledger_tag_media_nofootprint            int64
 *   offset 264  ledger_tag_media_nofootprint_compressed int64
 *   offset 272  ledger_tag_graphics_footprint           int64   ← GPU/Metal textures
 *   offset 280  ledger_tag_graphics_footprint_compressed int64
 *   offset 288  ledger_tag_graphics_nofootprint         int64
 *   offset 296  ledger_tag_graphics_nofootprint_compressed int64
 *   offset 304  ledger_tag_neural_footprint             int64
 *   offset 312  ledger_tag_neural_footprint_compressed  int64
 *   offset 320  ledger_tag_neural_nofootprint           int64
 *   offset 328  ledger_tag_neural_nofootprint_compressed int64
 *   TASK_VM_INFO_REV3_COUNT = 84 (84 × 4 = 336 bytes)
 * </pre>
 */
@Library("System")
class MachMemInfo {

    static {
        Bro.bind(MachMemInfo.class);
    }

    /**
     * {@code sun.misc.Unsafe} instance used for off-heap memory management.
     * Available in robovmx (Android-based runtime) and never null on a
     * functioning device; null only if the reflective lookup fails, in which
     * case all public methods return {@code -1} or {@code null}.
     */
    private static final Unsafe UNSAFE;
    static {
        Unsafe u = null;
        try {
            java.lang.reflect.Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            u = (Unsafe) f.get(null);
        } catch (Throwable ignored) { /* leave null; public methods guard */ }
        UNSAFE = u;
    }

    /** Current task Mach port ({@code mach_task_self_} global variable). */
    @GlobalValue(symbol = "mach_task_self_")
    private static native int machTaskSelf();

    /**
     * Binding for:
     * <pre>
     * kern_return_t task_info(task_name_t target_task, task_flavor_t flavor,
     *                         task_info_t task_info_out,
     *                         mach_msg_type_number_t *task_info_outCnt);
     * </pre>
     */
    @Bridge(symbol = "task_info")
    private static native int taskInfo(int targetTask, int flavor,
            @Pointer long taskInfoOut, @Pointer long taskInfoOutCnt);

    /**
     * Returns the process physical footprint in megabytes, or a negative
     * value on failure:
     * <ul>
     *   <li>{@code -1} — exception thrown, or {@code Unsafe} not available.</li>
     *   <li>Negative {@code kern_return_t} — the Mach call returned a
     *       non-zero status; negate the return value to get the raw Mach
     *       error code (e.g. {@code KERN_INVALID_ARGUMENT = 4},
     *       {@code KERN_FAILURE = 5}).</li>
     * </ul>
     *
     * <p>Physical footprint is what iOS jetsam monitors.  It includes the Java
     * heap (Boehm GC), loaded native code, GPU textures, and all other resident
     * memory — not just what {@link Runtime#totalMemory()} reports.
     */
    static long getPhysicalFootprintMB() {
        if (UNSAFE == null) return -1;
        long bufAddr = 0, cntAddr = 0;
        try {
            // Allocate 160 bytes (40 natural_t words) — enough for TASK_VM_INFO
            // rev1 (38 words = 152 bytes), with 8 bytes of headroom.
            // phys_footprint is at byte offset 144 (mach_vm_size_t = uint64).
            bufAddr = UNSAFE.allocateMemory(160);
            cntAddr = UNSAFE.allocateMemory(4);
            UNSAFE.putInt(cntAddr, 40); // capacity passed as natural_t-word count
            int kr = taskInfo(machTaskSelf(), 22 /* TASK_VM_INFO */, bufAddr, cntAddr);
            if (kr == 0) { // KERN_SUCCESS
                return UNSAFE.getLong(bufAddr + 144) >> 20; // bytes → MB
            }
            return -(long) kr; // return negative kern_return_t for diagnosis
        } catch (Throwable t) {
            return -1;
        } finally {
            if (bufAddr != 0) UNSAFE.freeMemory(bufAddr);
            if (cntAddr != 0) UNSAFE.freeMemory(cntAddr);
        }
    }

    /**
     * Returns a compact summary string of key {@code task_vm_info_data_t}
     * fields, using a single {@code task_info(TASK_VM_INFO)} call with a
     * REV3-sized buffer (84 natural_t words = 336 bytes).
     *
     * <p>Fields reported (all in MB):
     * <ul>
     *   <li>{@code phys} — {@code phys_footprint}: total jetsam-monitored
     *       footprint (the value that triggers "exceeded mem limit").</li>
     *   <li>{@code internal} — {@code internal}: anonymous/Java-heap pages
     *       (correlates with {@link Runtime#totalMemory()}, but also includes
     *       other anonymous mappings).</li>
     *   <li>{@code compressed} — {@code compressed}: pages currently
     *       compressed by the kernel memory compressor (swapped out).</li>
     *   <li>{@code phys_peak} — {@code ledger_phys_footprint_peak}: highest
     *       {@code phys_footprint} value recorded since launch (REV3).</li>
     *   <li>{@code gfx} — {@code ledger_tag_graphics_footprint}: GPU/Metal
     *       texture memory counted in the jetsam footprint — the dominant
     *       non-Java memory consumer in Forge (REV3).</li>
     *   <li>{@code gfx_nofp} — {@code ledger_tag_graphics_nofootprint}: GPU
     *       texture memory <em>not</em> counted in the jetsam footprint, e.g.
     *       IOSurface-backed textures on some devices (REV3).</li>
     * </ul>
     *
     * <p>If the kernel returns fewer words than REV3 requires (older iOS), the
     * REV3 fields fall back to {@code -1} while the REV1 fields remain valid.
     * Returns {@code null} if {@code Unsafe} is unavailable or any exception
     * is thrown.
     *
     * <p>Example output:
     * <pre>
     *   phys=1140MB internal=890MB compressed=120MB phys_peak=1155MB gfx=230MB gfx_nofp=0MB
     * </pre>
     */
    static String getVmInfoLine() {
        if (UNSAFE == null) return null;
        // REV3 buffer: 84 words × 4 bytes = 336 bytes; add 8 bytes of headroom.
        final int REV3_WORDS = 84;
        final int REV1_WORDS = 38;
        long bufAddr = 0, cntAddr = 0;
        try {
            bufAddr = UNSAFE.allocateMemory(344);
            cntAddr = UNSAFE.allocateMemory(4);
            UNSAFE.putInt(cntAddr, REV3_WORDS);
            int kr = taskInfo(machTaskSelf(), 22 /* TASK_VM_INFO */, bufAddr, cntAddr);
            if (kr != 0) return "kr=" + kr;

            int wordsReturned = UNSAFE.getInt(cntAddr); // how many words the kernel filled

            long phys      = UNSAFE.getLong(bufAddr + 144) >> 20; // REV1
            long internal  = UNSAFE.getLong(bufAddr +  48) >> 20; // REV1
            long compressed = UNSAFE.getLong(bufAddr + 120) >> 20; // REV1

            // REV3 fields — valid only if kernel returned at least REV3_WORDS words.
            long physPeak  = wordsReturned >= REV3_WORDS ? UNSAFE.getLong(bufAddr + 168) >> 20 : -1;
            long gfx       = wordsReturned >= REV3_WORDS ? UNSAFE.getLong(bufAddr + 272) >> 20 : -1;
            long gfxNofp   = wordsReturned >= REV3_WORDS ? UNSAFE.getLong(bufAddr + 288) >> 20 : -1;

            return "phys=" + phys + "MB"
                    + " internal=" + internal + "MB"
                    + " compressed=" + compressed + "MB"
                    + " phys_peak=" + physPeak + "MB"
                    + " gfx=" + gfx + "MB"
                    + " gfx_nofp=" + gfxNofp + "MB";
        } catch (Throwable t) {
            return null;
        } finally {
            if (bufAddr != 0) UNSAFE.freeMemory(bufAddr);
            if (cntAddr != 0) UNSAFE.freeMemory(cntAddr);
        }
    }
}
