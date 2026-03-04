package java.nio.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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
        File parent = path.toFile().getParentFile();
        if (parent != null) parent.mkdirs();
        return new FileOutputStream(path.toFile());
    }

    public static Stream<Path> walk(Path start) throws IOException {
        if (!start.toFile().exists()) {
            throw new IOException("Path does not exist: " + start);
        }
        List<Path> paths = new ArrayList<>();
        walkTree(start.toFile(), paths);
        return Stream.fromCollection(paths);
    }

    private static void walkTree(File file, List<Path> paths) {
        paths.add(new Path(file));
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    walkTree(child, paths);
                }
            }
        }
    }

    public static Path copy(Path source, Path target, CopyOption... options) throws IOException {
        File srcFile = source.toFile();
        File dstFile = target.toFile();
        if (srcFile.isDirectory()) {
            dstFile.mkdirs();
        } else {
            File parent = dstFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileInputStream in = new FileInputStream(srcFile);
                 FileOutputStream out = new FileOutputStream(dstFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
        }
        return target;
    }

    public static Path createDirectories(Path dir) throws IOException {
        dir.toFile().mkdirs();
        return dir;
    }
}

