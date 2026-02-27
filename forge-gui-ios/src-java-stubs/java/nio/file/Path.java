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

    public Path resolve(Path other) {
        if (other.file.isAbsolute()) {
            return other;
        }
        return new Path(new File(file, other.file.getPath()));
    }

    public Path relativize(Path other) {
        String basePath = file.getAbsolutePath();
        String otherPath = other.file.getAbsolutePath();
        if (otherPath.startsWith(basePath)) {
            String rel = otherPath.substring(basePath.length());
            if (rel.startsWith(File.separator)) {
                rel = rel.substring(File.separator.length());
            }
            return new Path(new File(rel));
        }
        return other;
    }

    @Override
    public String toString() {
        return file.getPath();
    }
}
