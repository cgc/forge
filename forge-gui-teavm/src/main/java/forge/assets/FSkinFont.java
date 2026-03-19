package forge.assets;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFont.BitmapFontData;
import com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.utils.Array;

import forge.Forge;
import forge.util.TextBounds;
import forge.util.Utils;

/**
 * Web-target override of {@code forge.assets.FSkinFont}.
 *
 * <h2>Why this override is needed</h2>
 * <p>The real {@code FSkinFont} cannot run as-is in the TeaVM browser target
 * for two reasons:
 * <ol>
 *   <li>{@code getCharacterSet()} calls
 *       {@code Files.newInputStream(Paths.get(...))} to load translation
 *       files.  TeaVM's JavaScript class library throws
 *       {@code UnsupportedOperationException} for that call, which is
 *       <em>not</em> an {@code IOException} and therefore escapes the
 *       existing {@code catch(IOException)} guard, crashing font generation.
 *   </li>
 *   <li>{@code updateFont()} / {@code generateFont()} write generated
 *       {@code .fnt} and {@code .png} files to {@code ForgeConstants.FONTS_DIR}
 *       via {@code Gdx.files.absolute()}, then reload them through the
 *       LibGDX {@code AssetManager}.  The browser has no persistent writable
 *       filesystem at absolute paths, so this round-trip is unnecessary and
 *       error-prone.
 *   </li>
 * </ol>
 *
 * <h2>Approach: real font rendering via FreeType Wasm</h2>
 * <p>Fonts are rendered using {@code FreeTypeFontGenerator} exactly as in
 * the native build.  The {@code gdx-freetype-teavm} library (declared in
 * {@code forge-gui-teavm/pom.xml}) provides FreeType compiled to WebAssembly
 * via Emscripten, making the full TTF-to-BitmapFont pipeline available in
 * the browser.
 *
 * <p>The key simplifications for the web target are:
 * <ul>
 *   <li>{@link #getCharacterSet(String)} returns only the default ASCII
 *       character set plus two commonly used Unicode characters.  Translation
 *       file loading (which requires {@code Files.newInputStream}) is
 *       intentionally omitted.  This means CJK and other non-ASCII characters
 *       in translated card names will not render until a browser-compatible
 *       i18n loading mechanism is added.
 *   </li>
 *   <li>Generated {@link BitmapFont} objects are kept in memory
 *       ({@code Forge.getAssets().fonts()}) and are NOT written to disk.
 *       Each application start regenerates fonts from the TTF file.
 *       FreeType Wasm runs at near-native speed, so this is not a
 *       significant performance concern.
 *   </li>
 *   <li>No {@code FileUtil.ensureDirectoryExists(FONTS_DIR)} call in the
 *       static initialiser (no disk cache directory needed).
 *   </li>
 * </ul>
 *
 * <h2>TTF file resolution</h2>
 * <p>{@link FSkin#getSkinFile(String)} is tried first.  If the returned
 * handle does not exist (e.g. because the skin directory was not copied to
 * the web assets), the well-known internal path
 * {@code res/skins/default/font1.ttf} is used as a fallback.
 */
public class FSkinFont {

    private static final int MIN_FONT_SIZE = 8;
    private static int MAX_FONT_SIZE = 72;
    private static final int MAX_FONT_SIZE_LESS_GLYPHS = 72;
    private static final int MAX_FONT_SIZE_MANY_GLYPHS = 36;
    private static final String TTF_FILE = "font1.ttf";

    // -------------------------------------------------------------------------
    // Static factory / cache
    // -------------------------------------------------------------------------

    public static FSkinFont get(final int unscaledSize) {
        return _get((int) Utils.scale(unscaledSize));
    }

    public static FSkinFont _get(final int scaledSize) {
        FSkinFont skinFont = Forge.getAssets().fonts().get(scaledSize);
        if (skinFont == null) {
            skinFont = new FSkinFont(scaledSize);
            Forge.getAssets().fonts().put(scaledSize, skinFont);
        }
        return skinFont;
    }

    public static FSkinFont forHeight(final float height) {
        int size = MIN_FONT_SIZE + 1;
        while (true) {
            FSkinFont f = _get(size);
            if (f != null && f.getLineHeight() > height) {
                return _get(size - 1);
            }
            size++;
        }
    }

    /** Pre-loads all supported font sizes for the given language. */
    public static void preloadAll(String language) {
        MAX_FONT_SIZE = (language.equals("zh-CN") || language.equals("ja-JP"))
                ? MAX_FONT_SIZE_MANY_GLYPHS
                : MAX_FONT_SIZE_LESS_GLYPHS;
        for (int size = MIN_FONT_SIZE; size <= MAX_FONT_SIZE; size++) {
            _get(size);
        }
    }

    /** No-op on the web target: fonts are generated in memory, not cached on disk. */
    public static void deleteCachedFiles() {
    }

    public static void updateAll() {
        for (FSkinFont skinFont : Forge.getAssets().fonts().values()) {
            skinFont.updateFont();
        }
    }

    /**
     * Returns only "None" on the web target.
     * CJK font TTF files stored in FONTS_DIR are not accessible via the
     * browser's virtual filesystem.
     */
    public static Iterable<String> getAllCJKFonts() {
        final Array<String> result = new Array<>();
        result.add("None");
        return result;
    }

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    private final int fontSize;
    private final float scale;
    BitmapFont font; // package-private: accessed by other forge.assets classes

    private FSkinFont(int fontSize0) {
        if (fontSize0 > MAX_FONT_SIZE) {
            scale = (float) fontSize0 / MAX_FONT_SIZE;
        } else if (fontSize0 < MIN_FONT_SIZE) {
            scale = (float) fontSize0 / MIN_FONT_SIZE;
        } else {
            scale = 1;
        }
        fontSize = fontSize0;
        updateFont();
    }

    // -------------------------------------------------------------------------
    // Text measurement helpers (identical to the real FSkinFont)
    // -------------------------------------------------------------------------

    static int indexOf(CharSequence text, char ch, int start) {
        final int n = text.length();
        for (; start < n; start++) {
            if (text.charAt(start) == ch) return start;
        }
        return n;
    }

    public int computeVisibleGlyphs(CharSequence str, int start, int end, float availableWidth) {
        if (font == null) return 0;
        BitmapFontData data = font.getData();
        int index = start;
        float width = 0;
        Glyph lastGlyph = null;
        availableWidth /= data.scaleX;

        for (; index < end; index++) {
            char ch = str.charAt(index);
            if (ch == '[' && data.markupEnabled) {
                index++;
                if (!(index < end && str.charAt(index) == '[')) {
                    while (index < end && str.charAt(index) != ']') index++;
                    continue;
                }
            }
            Glyph g = data.getGlyph(ch);
            if (g != null) {
                if (lastGlyph != null) width += lastGlyph.getKerning(ch);
                if ((width + g.xadvance) - availableWidth > 0.001f) break;
                width += g.xadvance;
                lastGlyph = g;
            }
        }
        return index - start;
    }

    public boolean isBreakChar(char c) {
        BitmapFontData data = font.getData();
        if (data.breakChars == null) return false;
        for (char br : data.breakChars) {
            if (c == br) return true;
        }
        return false;
    }

    static boolean isWhitespace(char c) {
        switch (c) {
            case '\n': case '\r': case '\t': case ' ': return true;
            default: return false;
        }
    }

    public TextBounds getBounds(CharSequence str) {
        updateScale();
        return getBounds(str, 0, str.length());
    }

    public TextBounds getBounds(CharSequence str, int start, int end) {
        if (font == null) return new TextBounds(0f, 0f);
        BitmapFontData data = font.getData();
        int width = 0;
        Glyph lastGlyph = null;

        while (start < end) {
            char ch = str.charAt(start++);
            if (ch == '[' && data.markupEnabled) {
                if (!(start < end && str.charAt(start) == '[')) {
                    while (start < end && str.charAt(start) != ']') start++;
                    start++;
                    continue;
                }
                start++;
            }
            lastGlyph = data.getGlyph(ch);
            if (lastGlyph != null) { width = lastGlyph.xadvance; break; }
        }
        while (start < end) {
            char ch = str.charAt(start++);
            if (ch == '[' && data.markupEnabled) {
                if (!(start < end && str.charAt(start) == '[')) {
                    while (start < end && str.charAt(start) != ']') start++;
                    start++;
                    continue;
                }
                start++;
            }
            Glyph g = data.getGlyph(ch);
            if (g != null) {
                width += lastGlyph.getKerning(ch);
                lastGlyph = g;
                width += g.xadvance;
            }
        }
        return new TextBounds(width * data.scaleX, data.capHeight);
    }

    public TextBounds getMultiLineBounds(CharSequence str) {
        updateScale();
        if (font == null) return new TextBounds(0f, 0f);
        BitmapFontData data = font.getData();
        int start = 0;
        float maxWidth = 0;
        int numLines = 0;
        int length = str.length();
        while (start < length) {
            int lineEnd = indexOf(str, '\n', start);
            float lineWidth = getBounds(str, start, lineEnd).width;
            maxWidth = Math.max(maxWidth, lineWidth);
            start = lineEnd + 1;
            numLines++;
        }
        return new TextBounds(maxWidth, data.capHeight + (numLines - 1) * data.lineHeight);
    }

    public TextBounds getWrappedBounds(CharSequence str, float wrapWidth) {
        updateScale();
        if (font == null) return new TextBounds(0f, 0f);
        BitmapFontData data = font.getData();
        if (wrapWidth <= 0) wrapWidth = Integer.MAX_VALUE;
        int start = 0;
        int numLines = 0;
        int length = str.length();
        float maxWidth = 0;
        while (start < length) {
            int newLine = indexOf(str, '\n', start);
            int lineEnd = start + computeVisibleGlyphs(str, start, newLine, wrapWidth);
            int nextStart = lineEnd + 1;
            if (lineEnd < newLine) {
                while (lineEnd > start) {
                    if (isWhitespace(str.charAt(lineEnd))) break;
                    if (isBreakChar(str.charAt(lineEnd - 1))) break;
                    lineEnd--;
                }
                if (lineEnd == start) {
                    if (nextStart > start + 1) nextStart--;
                    lineEnd = nextStart;
                } else {
                    nextStart = lineEnd;
                    while (nextStart < length) {
                        char c = str.charAt(nextStart);
                        if (!isWhitespace(c)) break;
                        nextStart++;
                        if (c == '\n') break;
                    }
                    while (lineEnd > start) {
                        if (!isWhitespace(str.charAt(lineEnd - 1))) break;
                        lineEnd--;
                    }
                }
            }
            if (lineEnd > start) {
                float lineWidth = getBounds(str, start, lineEnd).width;
                maxWidth = Math.max(maxWidth, lineWidth);
            }
            start = nextStart;
            numLines++;
        }
        return new TextBounds(maxWidth, data.capHeight + (numLines - 1) * data.lineHeight);
    }

    public float getAscent() {
        if (font == null) return 0f;
        updateScale();
        return font.getAscent();
    }

    public float getCapHeight() {
        if (font == null) return 0f;
        updateScale();
        return font.getCapHeight();
    }

    public float getLineHeight() {
        if (font == null) return 0f;
        updateScale();
        return font.getLineHeight();
    }

    public void draw(Batch batch, String text, Color color, float x, float y,
            float w, boolean wrap, int horzAlignment) {
        updateScale();
        font.setColor(color);
        font.draw(batch, text, x, y, w, horzAlignment, wrap);
    }

    private void updateScale() {
        try {
            if (font.getScaleX() != scale) {
                font.getData().setScale(scale);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean canShrink() {
        return fontSize > MIN_FONT_SIZE;
    }

    public boolean canIncrease() {
        return MAX_FONT_SIZE - fontSize > 2;
    }

    public FSkinFont shrink() {
        return _get(fontSize - 1);
    }

    public FSkinFont increase() {
        return _get(fontSize + 3);
    }

    // -------------------------------------------------------------------------
    // Font generation
    // -------------------------------------------------------------------------

    /**
     * Returns the character set used when generating a font.
     *
     * <p>On the web target, reading translation files via
     * {@code Files.newInputStream()} is unavailable.  We return only the
     * LibGDX default ASCII character set plus the bullet (•) and em-dash (—)
     * characters that Forge uses for card text rendering.  This is sufficient
     * for English; non-ASCII translation characters will fall back to the
     * replacement glyph.
     *
     * @param langCode the locale code (e.g. {@code "en-US"}, {@code "zh-CN"});
     *                 kept for API compatibility with the real {@code FSkinFont}
     *                 even though it is not used on the web target where
     *                 translation files are not accessible.
     */
    @SuppressWarnings("unused") // langCode kept for API compatibility
    public String getCharacterSet(String langCode) {
        return FreeTypeFontGenerator.DEFAULT_CHARS + "•—";
    }

    private void updateFont() {
        // Determine the font size that backs this instance (clamped to range).
        final int baseSize;
        if (scale != 1) {
            baseSize = (fontSize > MAX_FONT_SIZE) ? MAX_FONT_SIZE : MIN_FONT_SIZE;
        } else {
            baseSize = fontSize;
        }
        generateFont(resolveTtfFile(), baseSize);
    }

    /**
     * Resolves the TTF file handle.
     * Tries the current skin's font first, then falls back to the default
     * skin's internal asset path.
     */
    private static FileHandle resolveTtfFile() {
        FileHandle ttfFile = FSkin.getSkinFile(TTF_FILE);
        if (ttfFile != null && ttfFile.exists()) {
            return ttfFile;
        }
        // Fallback: load from the default skin shipped as a web asset.
        return Gdx.files.internal("res/skins/default/" + TTF_FILE);
    }

    /**
     * Generates a {@link BitmapFont} for {@code fontSize} using the
     * FreeType Wasm backend provided by {@code gdx-freetype-teavm}.
     *
     * <p>Unlike the desktop implementation, fonts are kept in memory and
     * are <em>not</em> written to disk.  The in-memory {@link BitmapFont}
     * is stored in {@link #font} and recycled via
     * {@code Forge.getAssets().fonts()}.
     */
    private void generateFont(final FileHandle ttfFile, final int fontSize) {
        if (ttfFile == null || !ttfFile.exists()) {
            return;
        }

        // Choose texture-page size proportional to the requested font size
        // to keep the atlas page count low.
        final int pageSize;
        if (fontSize >= 50) {
            pageSize = 1024;
        } else if (fontSize >= 20) {
            pageSize = 512;
        } else {
            pageSize = 256;
        }

        final FreeTypeFontGenerator generator = new FreeTypeFontGenerator(ttfFile);
        try {
            final PixmapPacker packer =
                    new PixmapPacker(pageSize, pageSize, Pixmap.Format.RGBA8888, 2, false);
            try {
                final FreeTypeFontParameter parameter = new FreeTypeFontParameter();
                parameter.characters = getCharacterSet(Forge.locale);
                parameter.size = fontSize;
                parameter.packer = packer;

                final FreeTypeFontGenerator.FreeTypeBitmapFontData fontData =
                        generator.generateData(parameter);
                final Array<PixmapPacker.Page> pages = packer.getPages();

                final Array<TextureRegion> textureRegions = new Array<>();
                for (int i = 0; i < pages.size; i++) {
                    PixmapPacker.Page p = pages.get(i);
                    Texture texture = new Texture(
                            new PixmapTextureData(
                                    p.getPixmap(), p.getPixmap().getFormat(), false, false)) {
                        @Override
                        public void dispose() {
                            super.dispose();
                            getTextureData().consumePixmap().dispose();
                        }
                    };
                    texture.setFilter(
                            Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
                    textureRegions.addAll(new TextureRegion(texture));
                }

                // Keep the generated font in memory; no disk-cache round-trip.
                font = new BitmapFont(fontData, textureRegions, true);
            } finally {
                packer.dispose();
            }
        } finally {
            generator.dispose();
        }
    }
}
