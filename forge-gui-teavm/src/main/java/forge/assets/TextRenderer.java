package forge.assets;

import com.badlogic.gdx.graphics.Color;

import forge.Graphics;
import forge.util.TextBounds;

/**
 * Web-target stub for {@code forge.assets.TextRenderer}.
 *
 * <p>The real {@code TextRenderer} declares a field of type
 * {@code java.text.BreakIterator}, which is absent from TeaVM's JavaScript
 * class library.  This causes a SEVERE compilation error (class not found)
 * that makes TeaVM emit a 0-byte {@code app.js}.
 *
 * <p>This stub removes the {@code BreakIterator} dependency entirely while
 * preserving the public API.  Text rendering in the browser will be handled
 * separately once the basic game loop is working.
 */
public class TextRenderer {

    public static String startColor(Color color) {
        return "<clr " + Color.rgba8888(color) + ">";
    }

    public static String endColor() {
        return "</clr>";
    }

    public TextRenderer() {
    }

    public TextRenderer(boolean parseReminderText0) {
    }

    public TextBounds getBounds(String text, FSkinFont skinFont) {
        return new TextBounds();
    }

    public TextBounds getWrappedBounds(String text, FSkinFont skinFont, float maxWidth) {
        return new TextBounds();
    }

    public void drawText(Graphics g, String text, FSkinFont skinFont, FSkinColor skinColor,
            float x, float y, float w, float h, float visibleStartY, float visibleHeight,
            boolean wrap0, int horzAlignment, boolean centerVertically) {
    }

    public void drawText(Graphics g, String text, FSkinFont skinFont, Color color,
            float x, float y, float w, float h, float visibleStartY, float visibleHeight,
            boolean wrap0, int horzAlignment, boolean centerVertically) {
    }
}
