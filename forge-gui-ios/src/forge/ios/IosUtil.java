package forge.ios;

import java.text.BreakIterator;
import java.text.CharacterIterator;
import java.util.Locale;

/**
 * iOS-only utilities for MobiVM/RoboVM runtime compatibility.
 *
 * <p>Methods here are substituted for problematic call sites at build time by
 * {@code scripts/StreamDesugar.java} — they are never invoked on other platforms.
 */
public final class IosUtil {

    private IosUtil() {}

    /**
     * Returns a {@link BreakIterator} suitable for line-breaking in the given locale.
     *
     * <p>MobiVM's robovm-rt includes Android's {@link BreakIterator} backed by ICU4C, but the
     * ICU data files ({@code icudt*.dat}) that the native {@code ubrk_open()} function requires
     * are not present on iOS — they live at Android-specific paths that do not exist on the
     * device.  Calling {@link BreakIterator#getLineInstance(Locale)} therefore always throws
     * {@code RuntimeException: ubrk_open failed: U_MISSING_RESOURCE_ERROR} on iOS.
     *
     * <p>This method is substituted for every {@code BreakIterator.getLineInstance(Locale)}
     * call site at iOS build time by {@code scripts/StreamDesugar.java} (Pattern 49).
     * It returns a {@link SimpleLineBreakIterator} that finds break positions after whitespace
     * characters using pure Java, requiring no native ICU data.
     *
     * <p>Note: {@code locale} is accepted for API compatibility but intentionally ignored;
     * whitespace-based line-breaking is locale-independent.
     */
    public static BreakIterator getLineBreakIterator(Locale locale) {
        return new SimpleLineBreakIterator();
    }

    /**
     * Pure-Java line-break iterator backed by whitespace detection.
     *
     * <p>Implements all abstract methods of {@link BreakIterator} without any ICU4C native
     * calls.  Break positions are reported after each whitespace character (space, tab,
     * newline, carriage-return), matching the behaviour expected by
     * {@code forge.assets.TextRenderer}.
     */
    private static final class SimpleLineBreakIterator extends BreakIterator {
        private String text = "";
        private int pos = 0;

        @Override public int first() { pos = 0; return 0; }
        @Override public int last()  { pos = text.length(); return pos; }
        @Override public int current() { return pos; }

        @Override
        public int next(int n) {
            if (n == 0) return pos;
            for (int i = 0; i < Math.abs(n); i++) {
                int r = n > 0 ? next() : previous();
                if (r == DONE) return DONE;
            }
            return pos;
        }

        @Override
        public int next() {
            if (pos >= text.length()) return DONE;
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return pos;
            }
            // text.length() is a valid break boundary (end of text); the next call
            // will find pos >= text.length() and return DONE, per BreakIterator contract.
            return pos;
        }

        @Override
        public int previous() {
            if (pos <= 0) return DONE;
            pos--;
            while (pos > 0 && text.charAt(pos - 1) != ' ' && text.charAt(pos - 1) != '\t'
                    && text.charAt(pos - 1) != '\n' && text.charAt(pos - 1) != '\r') {
                pos--;
            }
            return pos;
        }

        @Override
        public int following(int offset) {
            if (offset >= text.length()) return DONE;
            pos = offset;
            return next();
        }

        @Override
        public CharacterIterator getText() {
            return new java.text.StringCharacterIterator(text);
        }

        @Override
        public void setText(CharacterIterator newText) {
            StringBuilder sb = new StringBuilder();
            for (char c = newText.first(); c != CharacterIterator.DONE; c = newText.next()) {
                sb.append(c);
            }
            text = sb.toString();
            pos = 0;
        }

        @Override
        public void setText(String newText) {
            text = newText != null ? newText : "";
            pos = 0;
        }
    }
}
