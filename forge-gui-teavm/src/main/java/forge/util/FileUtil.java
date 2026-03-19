/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.util;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * Web-target stub replacement for {@code forge.util.FileUtil}.
 *
 * <p>Identical to the original except that the two methods using
 * {@code java.nio.file.Files} / {@code File.toPath()} have been rewritten to
 * use plain {@code java.io} streams instead.  Those NIO APIs are absent from
 * TeaVM's JavaScript class library, causing a SEVERE compilation error that
 * produces a 0-byte {@code app.js}.
 *
 * <p>Maven places the current module's compiled classes before
 * transitive-dependency JARs on the TeaVM compilation classpath, so this stub
 * silently shadows {@code forge-core}'s {@code FileUtil} for the web target
 * without modifying any shared module.
 */
public final class FileUtil {

    private FileUtil() {
        throw new AssertionError();
    }

    public static String pathCombine(String path1, String path2) {
        File file1 = new File(path1);
        File file2 = new File(file1, path2);
        return file2.getPath();
    }

    public static boolean doesFileExist(final String filename) {
        final File f = new File(filename);
        return f.exists();
    }

    public static boolean isDirectoryWithFiles(final String path) {
        if (path == null) { return false; }
        final File f = new File(path);
        final String[] fileList = f.list();
        return fileList != null && fileList.length > 0;
    }

    public static boolean ensureDirectoryExists(final String path) {
        return ensureDirectoryExists(new File(path));
    }

    public static boolean ensureDirectoryExists(final File dir) {
        return (dir.exists() && dir.isDirectory()) || dir.mkdirs();
    }

    public static boolean deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            for (String filename : dir.list()) {
                if (!deleteDirectory(new File(dir, filename))) {
                    return false;
                }
            }
        }
        return dir.delete();
    }

    public static boolean deleteFile(String filename) {
        try {
            File file = new File(filename);
            return file.delete();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * No-op on the web target: there is no writable local filesystem in a browser.
     */
    public static void copyFile(String sourceFilename, String destFilename) {
    }

    /** No-op on the web target. */
    public static void writeFile(String filename, String text) {
    }

    /** No-op on the web target. */
    public static void writeFile(File file, String text) {
    }

    /** No-op on the web target. */
    public static void writeFile(String filename, List<String> data) {
    }

    /** No-op on the web target. */
    public static void writeFile(File file, Collection<?> data) {
    }

    public static String readFileToString(String filename) {
        return readFileToString(new File(filename));
    }

    public static String readFileToString(File file) {
        return TextUtil.join(readFile(file), "\n");
    }

    public static List<String> readFile(final String filename) {
        return FileUtil.readFile(new File(filename));
    }

    public static List<String> readFile(final File file) {
        try {
            if ((file == null) || !file.exists()) {
                return new ArrayList<>();
            }
            return FileUtil.readAllLines(file, false);
        } catch (final Exception ex) {
            throw new RuntimeException("FileUtil : readFile() error, " + ex);
        }
    }

    public static List<String> readAllLines(final Reader reader) {
        return FileUtil.readAllLines(reader, false);
    }

    public static List<String> readAllLines(final Reader reader, final boolean mayTrim) {
        final List<String> list = new ArrayList<>();
        try {
            final BufferedReader in = new BufferedReader(reader);
            String line;
            while ((line = in.readLine()) != null) {
                if (mayTrim) {
                    line = line.trim();
                }
                list.add(line);
            }
            in.close();
        } catch (final IOException ex) {
            throw new RuntimeException("FileUtil : readAllLines() error, " + ex);
        }
        return list;
    }

    /**
     * Reads all lines from a file using a plain {@code FileInputStream}.
     * The original used {@code Files.newInputStream(file.toPath())} which is
     * absent from TeaVM's JS class library.
     */
    public static List<String> readAllLines(final File file, final boolean mayTrim) {
        final List<String> list = new ArrayList<>();
        try {
            final BufferedReader in = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                if (mayTrim) {
                    line = line.trim();
                }
                list.add(line);
            }
            in.close();
        } catch (final IOException ex) {
            throw new RuntimeException("FileUtil : readAllLines() error, " + ex);
        }
        return list;
    }

    public static List<Pair<String, String>> readNameUrlFile(String nameUrlFile) {
        Pattern lineSplitter = Pattern.compile(Pattern.quote(" "));
        Pattern replacer = Pattern.compile(Pattern.quote("%20"));

        List<Pair<String, String>> list = new ArrayList<>();

        for (String line : readFile(nameUrlFile)) {
            if (StringUtils.isBlank(line) || line.startsWith("#")) {
                continue;
            }

            String[] parts = lineSplitter.split(line, 2);
            if (2 == parts.length) {
                list.add(Pair.of(replacer.matcher(parts[0]).replaceAll(" "), parts[1]));
            } else {
                Pattern pathSplitter = Pattern.compile(Pattern.quote("/"));
                String[] pathParts = pathSplitter.split(parts[0]);
                String last = pathParts[pathParts.length - 1];
                list.add(Pair.of(replacer.matcher(last).replaceAll(" "), parts[0]));
            }
        }

        return list;
    }

    public static String readFileToString(final URL url) {
        return TextUtil.join(readFile(url), "\n");
    }

    public static List<String> readFile(final URL url) {
        final List<String> lines = new ArrayList<>();
        ThreadUtil.executeWithTimeout((Callable<Void>) () -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    lines.add(line);
                }
            }
            return null;
        }, 5000);
        return lines;
    }

    public static String getParent(final String resourcePath) {
        File f = new File(resourcePath);
        if (f.getParentFile().getName() != null) {
            return f.getParentFile().getName();
        }
        return "";
    }
}
