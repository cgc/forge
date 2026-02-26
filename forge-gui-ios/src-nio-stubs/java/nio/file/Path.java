package java.nio.file;

import java.io.File;

/** Stub for RoboVM: java.nio.file is absent from the MobiVM runtime. */
public class Path {
    final File file;

    public Path(File file) {
        this.file = file;
    }

    public File toFile() {
        return file;
    }

    @Override
    public String toString() {
        return file.getPath();
    }
}
