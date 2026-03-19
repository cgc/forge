package forge;

/**
 * Web-target stub for {@code forge.FrameRate}.
 *
 * <p>The real {@code FrameRate} creates a {@code new BitmapFont()} with the
 * LibGDX no-arg constructor, which internally calls
 * {@code Gdx.files.classpath("com/badlogic/gdx/utils/lsans-15.fnt")} to load
 * the built-in bitmap font.  In the browser environment (TeaVM), classpath
 * resources are not served by the HTTP server, so the
 * {@code WebFileHandle.read()} call throws a
 * {@code GdxRuntimeException("File not found: ...")} at startup.
 *
 * <p>The frame-rate overlay is a debug aid only and has no effect on gameplay.
 * This stub provides the same public API as the real class but all methods are
 * no-ops, so TeaVM compiles and tree-shakes away the font/batch machinery
 * entirely.
 */
public class FrameRate {

    public FrameRate() {
        // No-op: BitmapFont classpath loading is unavailable in the browser.
    }

    public void resize(int screenWidth, int screenHeight) {
        // No-op.
    }

    public void update(int loadedCardSize, float toAlloc) {
        // No-op.
    }

    public void render() {
        // No-op.
    }

    public void dispose() {
        // No-op.
    }
}
