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
package forge.localinstance.properties;

import java.io.File;
import java.util.Collections;
import java.util.Map;

import forge.util.FileUtil;

/**
 * Web-target stub replacement for {@code ForgeProfileProperties}.
 *
 * <p>The original implementation (in {@code forge-gui}) uses
 * {@code java.nio.file.Files.newInputStream(File.toPath())} and
 * {@code System.getenv()} – APIs not available in TeaVM's JavaScript
 * class library.  Because Maven places the current module's compiled classes
 * before transitive-dependency JARs on both the {@code javac} and TeaVM
 * compilation classpaths, this stub shadows the real class for the web target
 * without modifying any other module.
 *
 * <h2>Web behaviour</h2>
 * <ul>
 *   <li>{@link #load} is a no-op.  There is no local filesystem in a browser;
 *       the profile {@code .properties} file cannot be read.  All directory
 *       paths fall back to the mobile-device defaults computed from
 *       {@link ForgeConstants#ASSETS_DIR}.</li>
 *   <li>The {@code set*()} mutators and {@link #save} are also no-ops: writing
 *       a preferences file is meaningless when there is no persistent
 *       filesystem.  Browser-local-storage persistence can be added later.</li>
 * </ul>
 */
public class ForgeProfileProperties {

    private static String userDir;
    private static String cacheDir;
    private static String cardPicsDir;
    private static Map<String, String> cardPicsSubDirs = Collections.emptyMap();
    private static String decksDir;
    private static String decksConstructedDir;

    private ForgeProfileProperties() { }

    /**
     * Initialises directory paths to the mobile/web defaults.
     * Does NOT attempt to read the profile {@code .properties} file (no NIO,
     * no {@code System.getenv}).
     */
    public static void load(boolean isUsingAppDirectory) {
        final String assetsDir = ForgeConstants.ASSETS_DIR;
        final String sep = File.separator;
        userDir    = assetsDir + "data"  + sep;
        cacheDir   = assetsDir + "cache" + sep;
        cardPicsDir = cacheDir + "pics" + sep + "cards" + sep;
        cardPicsSubDirs = Collections.emptyMap();
        decksDir   = userDir + "decks" + sep;
        decksConstructedDir = decksDir + "constructed" + sep;

        FileUtil.ensureDirectoryExists(userDir);
        FileUtil.ensureDirectoryExists(cacheDir);
        FileUtil.ensureDirectoryExists(cardPicsDir);
    }

    public static String getUserDir() { return userDir; }
    public static void setUserDir(final String userDir0) {
        userDir = userDir0;
    }

    public static String getCacheDir() { return cacheDir; }
    public static void setCacheDir(final String cacheDir0) {
        if (cardPicsDir != null && cacheDir != null) {
            final int idx = cardPicsDir.indexOf(cacheDir);
            if (idx != -1) {
                cardPicsDir = cacheDir0 + cardPicsDir.substring(idx + cacheDir.length());
            }
        }
        cacheDir = cacheDir0;
    }

    public static String getCardPicsDir() { return cardPicsDir; }
    public static void setCardPicsDir(final String cardPicsDir0) {
        cardPicsDir = cardPicsDir0;
    }

    public static Map<String, String> getCardPicsSubDirs() { return cardPicsSubDirs; }

    public static String getDecksDir() { return decksDir; }
    public static void setDecksDir(final String decksDir0) { decksDir = decksDir0; }

    public static String getDecksConstructedDir() { return decksConstructedDir; }
    public static void setDecksConstructedDir(final String decksConstructedDir0) {
        decksConstructedDir = decksConstructedDir0;
    }
}
