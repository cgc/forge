package java.nio.file;

import java.io.File;

/** Stub for RoboVM: java.nio.file is absent from the MobiVM runtime. */
public final class Paths {
    private Paths() {}

    public static Path get(String first, String... more) {
        File f = new File(first);
        for (String part : more) {
            f = new File(f, part);
        }
        return new Path(f);
    }
}
