package java.util;

/**
 * Stub java.util.StringJoiner for MobiVM: java.util.StringJoiner (Java 8) is
 * absent from robovm-rt's Android 4.4-era class library.
 *
 * <p>commons-lang3 3.x uses StringJoiner internally via LangCollectors.joining()
 * (called by StringUtils.join()).  Without this stub any game that loads cards
 * with keywords throws NoClassDefFoundError: java/util/StringJoiner at
 * Card.getKeywordKey() → CardView.updateKeywords() → Card.addIntrinsicKeywords().
 *
 * <p>This is a complete, faithful implementation backed by a StringBuilder.
 */
public final class StringJoiner {

    private final String delimiter;
    private final String prefix;
    private final String suffix;

    private String emptyValue;
    private final StringBuilder buf = new StringBuilder();
    private boolean hasContent = false;

    public StringJoiner(CharSequence delimiter) {
        this(delimiter, "", "");
    }

    public StringJoiner(CharSequence delimiter, CharSequence prefix, CharSequence suffix) {
        if (delimiter == null) throw new NullPointerException("delimiter must not be null");
        if (prefix == null) throw new NullPointerException("prefix must not be null");
        if (suffix == null) throw new NullPointerException("suffix must not be null");
        this.delimiter = delimiter.toString();
        this.prefix = prefix.toString();
        this.suffix = suffix.toString();
        this.emptyValue = this.prefix + this.suffix;
    }

    public StringJoiner setEmptyValue(CharSequence emptyValue) {
        if (emptyValue == null) throw new NullPointerException("emptyValue must not be null");
        this.emptyValue = emptyValue.toString();
        return this;
    }

    @Override
    public String toString() {
        if (!hasContent) return emptyValue;
        return prefix + buf.toString() + suffix;
    }

    public StringJoiner add(CharSequence newElement) {
        if (hasContent) {
            buf.append(delimiter);
        }
        buf.append(newElement == null ? "null" : newElement);
        hasContent = true;
        return this;
    }

    public StringJoiner merge(StringJoiner other) {
        if (other.hasContent) {
            if (hasContent) {
                buf.append(delimiter);
            }
            buf.append(other.buf);
            hasContent = true;
        }
        return this;
    }

    public int length() {
        return hasContent
                ? prefix.length() + buf.length() + suffix.length()
                : emptyValue.length();
    }
}
