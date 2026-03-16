package forge;

import forge.item.PaperCard;

import java.io.File;
import java.util.HashSet;
import java.util.Map;

/**
 * Web-target stub for {@code forge.ImageKeys}.
 *
 * <p>The real {@code ImageKeys.getImageFile()} calls
 * {@code ThreadUtil.getServicePool().submit(...)}, where
 * {@code java.util.concurrent.ExecutorService} is absent from TeaVM's JS
 * class library.  This stub replaces the whole class, keeping all public
 * constants and returning {@code null} from file-lookup methods (images are
 * served as browser assets, not filesystem files).
 */
public final class ImageKeys {

    public static final String CARD_PREFIX            = "c:";
    public static final String TOKEN_PREFIX           = "t:";
    public static final String ICON_PREFIX            = "i:";
    public static final String BOOSTER_PREFIX         = "b:";
    public static final String FATPACK_PREFIX         = "f:";
    public static final String BOOSTERBOX_PREFIX      = "x:";
    public static final String PRECON_PREFIX          = "p:";
    public static final String TOURNAMENTPACK_PREFIX  = "o:";
    public static final String ADVENTURECARD_PREFIX   = "a:";

    public static final String HIDDEN_CARD    = "hidden";
    public static final String MORPH_IMAGE    = "morph";
    public static final String MANIFEST_IMAGE = "manifest";
    public static final String CLOAKED_IMAGE  = "cloaked";
    public static final String FORETELL_IMAGE = "foretell";
    public static final String BLESSING_IMAGE = "blessing";
    public static final String INITIATIVE_IMAGE = "initiative";
    public static final String MONARCH_IMAGE  = "monarch";
    public static final String THE_RING_IMAGE = "the_ring";
    public static final String RADIATION_IMAGE = "radiation";
    public static final String SPEED_IMAGE    = "speed";
    public static final String MAX_SPEED_IMAGE = "max_speed";

    public static final String BACKFACE_POSTFIX = "$alt";
    public static final String SPECFACE_W = "$wspec";
    public static final String SPECFACE_U = "$uspec";
    public static final String SPECFACE_B = "$bspec";
    public static final String SPECFACE_R = "$rspec";
    public static final String SPECFACE_G = "$gspec";

    public static String ADVENTURE_CARD_PICS_DIR;

    public static HashSet<String> missingCards = new HashSet<>();

    private ImageKeys() { }

    public static void setIsLibGDXPort(boolean value) { }

    public static void initializeDirs(String cards, Map<String, String> cardsSub,
            String tokens, String icons, String boosters, String fatPacks,
            String boosterBoxes, String precons, String tournamentPacks) {
    }

    public static String getTokenKey(String tokenName) {
        return TOKEN_PREFIX + tokenName;
    }

    public static String getTokenImageName(String tokenKey) {
        if (!tokenKey.startsWith(TOKEN_PREFIX)) { return null; }
        return tokenKey.substring(TOKEN_PREFIX.length());
    }

    public static void clearMissingCards() {
        missingCards.clear();
    }

    /** Returns {@code null} — images are browser assets on the web target. */
    public static File getCachedCardsFile(String key) {
        return null;
    }

    /** Returns {@code null} — images are browser assets on the web target. */
    public static File getImageFile(String key) {
        return null;
    }

    public static String getSetFolder(String edition) {
        return edition;
    }

    public static boolean hasSetLookup(String filename) {
        return false;
    }

    /** Returns {@code null} — set lookup uses browser assets on the web target. */
    public static File setLookUpFile(String filename, String fullborderFile) {
        return null;
    }

    public static boolean hasImage(PaperCard pc) {
        return false;
    }

    public static boolean hasImage(PaperCard pc, boolean update) {
        return false;
    }
}
