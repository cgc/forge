package java.nio.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Stub for RoboVM: java.nio.file is absent from the MobiVM runtime. */
public final class Files {
    private Files() {}

    public static boolean exists(Path path, LinkOption... options) {
        return path.toFile().exists();
    }

    public static InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return new FileInputStream(path.toFile());
    }

    public static OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        File f = path.toFile();
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        return new FileOutputStream(f);
    }

    public static java.util.stream.Stream<Path> walk(Path start, FileVisitOption... options) throws IOException {
        List<Path> result = new ArrayList<>();
        walkRecursive(start.toFile(), result);
        return java.util.stream.Stream.fromCollection(result);
    }

    private static void walkRecursive(File dir, List<Path> result) {
        result.add(new Path(dir));
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    walkRecursive(child, result);
                }
            }
        }
    }

    public static Path createDirectories(Path dir, java.nio.file.attribute.FileAttribute<?>... attrs) throws IOException {
        File f = dir.toFile();
        if (!f.exists() && !f.mkdirs()) {
            throw new IOException("Failed to create directories: " + dir);
        }
        return dir;
    }

    public static Path copy(Path source, Path target, CopyOption... options) throws IOException {
        File src = source.toFile();
        File dst = target.toFile();
        File dstParent = dst.getParentFile();
        if (dstParent != null) dstParent.mkdirs();
        try (InputStream is = new FileInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
        }
        return target;
    }
}
