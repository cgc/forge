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
        return new Path(new File(this.file, other.toString()));
    }

    public Path resolve(String other) {
        return new Path(new File(this.file, other));
    }

    public Path relativize(Path other) {
        String thisStr = this.file.getAbsolutePath();
        String otherStr = other.toFile().getAbsolutePath();
        if (otherStr.startsWith(thisStr)) {
            String rel = otherStr.substring(thisStr.length());
            if (rel.startsWith(File.separator)) {
                rel = rel.substring(1);
            }
            return new Path(new File(rel));
        }
        throw new IllegalArgumentException("Cannot relativize " + other + " against " + this);
    }

    @Override
    public String toString() {
        return file.getPath();
    }
}

