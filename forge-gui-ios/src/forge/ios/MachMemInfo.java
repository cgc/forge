package forge.ios;

import org.robovm.rt.VM;
import org.robovm.rt.bro.Bro;
import org.robovm.rt.bro.annotation.Bridge;
import org.robovm.rt.bro.annotation.GlobalValue;
import org.robovm.rt.bro.annotation.Library;
import org.robovm.rt.bro.annotation.Pointer;

/**
 * Mach kernel bindings for reading the iOS process physical-footprint.
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
 * <p>{@code task_vm_info_data_t} layout on arm64 (relevant excerpt):
 * <pre>
 *   offset   0  virtual_size           uint64
 *   offset   8  region_count           int32
 *   offset  12  page_size              int32
 *   offset  16  resident_size          uint64
 *   ...        (12 more uint64 fields, offsets 24–136)
 *   offset 144  phys_footprint         uint64  ← TASK_VM_INFO rev1 addition
 * </pre>
 * {@code TASK_VM_INFO_REV1_COUNT = 38} natural_t words (38 × 4 = 152 bytes)
 * is the minimum count that covers {@code phys_footprint}.
 */
@Library("System")
class MachMemInfo {

    static {
        Bro.bind(MachMemInfo.class);
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
     *   <li>{@code -1} — exception thrown or native allocation failed
     *       ({@code VM.allocateMemory} returned 0).</li>
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
        long bufAddr = 0, cntAddr = 0;
        try {
            // Allocate 160 bytes (40 natural_t words) — enough for TASK_VM_INFO
            // rev1 (38 words = 152 bytes), with 8 bytes of headroom.
            // phys_footprint is at byte offset 144 (mach_vm_size_t = uint64).
            bufAddr = VM.allocateMemory(160);
            cntAddr = VM.allocateMemory(4);
            // VM.allocateMemory() returns 0 on allocation failure (no throw).
            // Passing 0 to taskInfo or VM.setInt would write/read address 0
            // and crash; bail out with -1 instead.
            if (bufAddr == 0 || cntAddr == 0) {
                return -1;
            }
            VM.setInt(cntAddr, 40); // capacity passed as natural_t-word count
            int kr = taskInfo(machTaskSelf(), 22 /* TASK_VM_INFO */, bufAddr, cntAddr);
            if (kr == 0) { // KERN_SUCCESS
                return VM.getLong(bufAddr + 144) >> 20; // bytes → MB
            }
            return -(long) kr; // return negative kern_return_t for diagnosis
        } catch (Throwable t) {
            return -1;
        } finally {
            if (bufAddr != 0) VM.freeMemory(bufAddr);
            if (cntAddr != 0) VM.freeMemory(cntAddr);
        }
    }
}
