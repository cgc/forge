package java.nio.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Stub for RoboVM: java.nio.file is absent from the MobiVM runtime. */
public final class Files {
    private Files() {}

    public static boolean exists(Path path) {
        return path.toFile().exists();
    }

    public static InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return new FileInputStream(path.toFile());
    }

    public static OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        File parent = path.toFile().getParentFile();
        if (parent != null) parent.mkdirs();
        return new FileOutputStream(path.toFile());
    }
}
