package forge.card;

import forge.ImageKeys;
import forge.deck.generation.IDeckGenPool;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.util.TextUtil;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Web-target stub for {@code forge.card.CardDb}.
 *
 * <p>The real constructor iterates card rules and calls
 * {@code addFaceToDbNames()} → {@code StringUtils.stripAccents()} →
 * {@code java.text.Normalizer} which is absent from TeaVM's JS class library.
 * This stub breaks the chain by providing a no-op constructor that never calls
 * {@code stripAccents}.
 *
 * <p>On the web target all databases are empty — card data is loaded via a
 * separate browser-asset mechanism.
 */
public final class CardDb implements ICardDatabase, IDeckGenPool {

    // Public constants that callers reference directly
    public static final String foilSuffix = "+";
    public static final char NameSetSeparator = '|';
    public static final String FlagPrefix = "#";
    public static final String FlagSeparator = "\t";

    /** Art preference for cards without an explicit set. */
    public enum CardArtPreference {
        LATEST_ART_ALL_EDITIONS(false, true),
        LATEST_ART_CORE_EXPANSIONS_REPRINT_ONLY(true, true),
        ORIGINAL_ART_ALL_EDITIONS(false, false),
        ORIGINAL_ART_CORE_EXPANSIONS_REPRINT_ONLY(true, false);

        public final boolean filterSets;
        public final boolean latestFirst;

        CardArtPreference(boolean filterIrregularSets, boolean latestSetFirst) {
            filterSets = filterIrregularSets;
            latestFirst = latestSetFirst;
        }

        public boolean isLatestFirst() { return latestFirst; }
        public boolean isCoreExpansionOnly() { return filterSets; }

        public boolean accept(CardEdition ed) {
            if (ed == null) { return false; }
            if (!filterSets) { return true; }
            CardEdition.Type t = ed.getType();
            return t == CardEdition.Type.CORE || t == CardEdition.Type.EXPANSION || t == CardEdition.Type.REPRINT;
        }
    }

    private static Map<String, String> artPrefs = new HashMap<>();

    public CardDb(Map<String, CardRules> rules, CardEdition.Collection editions0, java.util.Set<String> filteredCards) {
    }

    /** Minimal stub for {@code CardDb.CardRequest}. */
    public static class CardRequest {
        public String cardName;
        public String edition;
        public int artIndex;
        public boolean isFoil;
        public String collectorNumber;
        public Map<String, String> flags;

        private CardRequest(String name, String edition, int artIndex, boolean isFoil,
                String collectorNumber, Map<String, String> flags) {
            this.cardName = name;
            this.edition = edition;
            this.artIndex = artIndex;
            this.isFoil = isFoil;
            this.collectorNumber = collectorNumber;
            this.flags = flags;
        }

        public static boolean isFoilCardName(final String name) {
            return name != null && name.trim().endsWith(foilSuffix);
        }

        public static String compose(String cardName, boolean isFoil) {
            if (isFoil) {
                return isFoilCardName(cardName) ? cardName : cardName + foilSuffix;
            }
            return isFoilCardName(cardName)
                    ? cardName.substring(0, cardName.length() - foilSuffix.length()) : cardName;
        }

        public static String compose(String cardName, String setCode) {
            if (setCode == null || setCode.isEmpty()) { setCode = ""; }
            cardName = cardName != null ? cardName : "";
            if (cardName.indexOf(NameSetSeparator) != -1) {
                CardRequest req = fromString(cardName);
                if (req != null) { cardName = req.cardName; }
            }
            return cardName + NameSetSeparator + setCode;
        }

        public static String compose(String cardName, String setCode, int artIndex) {
            return compose(cardName, setCode) + NameSetSeparator + Math.max(artIndex, IPaperCard.DEFAULT_ART_INDEX);
        }

        public static String compose(String cardName, String setCode, String collectorNumber) {
            return compose(cardName, setCode) + NameSetSeparator + preprocessCollectorNumber(collectorNumber);
        }

        public static String compose(String cardName, String setCode, int artIndex, Map<String, String> flags) {
            String base = compose(cardName, setCode) + NameSetSeparator
                    + Math.max(artIndex, IPaperCard.DEFAULT_ART_INDEX);
            return flags == null ? base : base + NameSetSeparator + FlagPrefix + "{}";
        }

        public static String compose(String cardName, String setCode, String collectorNumber,
                Map<String, String> flags) {
            String base = compose(cardName, setCode) + NameSetSeparator
                    + preprocessCollectorNumber(collectorNumber);
            return flags == null || flags.isEmpty() ? base : base + NameSetSeparator + FlagPrefix + "{}";
        }

        public static String compose(PaperCard card) {
            return compose(compose(card.getName(), card.isFoil()), card.getEdition(),
                    card.getCollectorNumber(), null);
        }

        public static String compose(String cardName, String setCode, int artIndex,
                String collectorNumber) {
            return compose(cardName, setCode, artIndex) + NameSetSeparator
                    + preprocessCollectorNumber(collectorNumber);
        }

        private static String preprocessCollectorNumber(String cn) {
            if (cn == null) { return ""; }
            cn = cn.trim();
            if (!cn.startsWith("[")) { cn = "[" + cn; }
            if (!cn.endsWith("]")) { cn += "]"; }
            return cn;
        }

        public static CardRequest fromString(String reqInfo) {
            if (reqInfo == null) { return null; }
            String[] info = TextUtil.split(reqInfo, NameSetSeparator);
            String cardName = info[0];
            boolean isFoil = false;
            int artIndex = IPaperCard.NO_ART_INDEX;
            String setCode = null;
            String collectorNumber = IPaperCard.NO_COLLECTOR_NUMBER;
            if (isFoilCardName(cardName)) {
                cardName = cardName.substring(0, cardName.length() - foilSuffix.length());
                isFoil = true;
            }
            int idx = 1;
            if (info.length > idx) {
                String seg = info[idx];
                // Check backface postfix before stripping
                String segClean = seg.replace(ImageKeys.BACKFACE_POSTFIX, "");
                if (!seg.startsWith("[") && !seg.startsWith(FlagPrefix)) {
                    try { artIndex = Integer.parseInt(segClean); idx++; }
                    catch (NumberFormatException e2) { setCode = seg; idx++; }
                }
            }
            if (info.length > idx && info[idx].startsWith("[")) {
                collectorNumber = info[idx].substring(1, info[idx].length() - 1);
                idx++;
            }
            if (CardEdition.UNKNOWN_CODE.equals(setCode)) { setCode = null; }
            if (setCode == null) {
                String pref = artPrefs.get(cardName);
                if (pref != null) {
                    String[] prefInfo = TextUtil.split(pref, NameSetSeparator);
                    if (prefInfo.length == 3) {
                        try {
                            return new CardRequest(prefInfo[0], prefInfo[1],
                                    Integer.parseInt(prefInfo[2]), isFoil,
                                    IPaperCard.NO_COLLECTOR_NUMBER, null);
                        } catch (NumberFormatException ignored) { }
                    }
                }
            }
            if (collectorNumber.equals(IPaperCard.NO_COLLECTOR_NUMBER) && artIndex == IPaperCard.NO_ART_INDEX) {
                artIndex = IPaperCard.DEFAULT_ART_INDEX;
            }
            return new CardRequest(cardName, setCode, artIndex, isFoil, collectorNumber, null);
        }
    }

    // ── ICardDatabase ────────────────────────────────────────────────────────

    @Override public Iterator<PaperCard> iterator() { return Collections.emptyIterator(); }
    @Override public PaperCard getCard(String n) { return null; }
    @Override public PaperCard getCard(String n, String e) { return null; }
    @Override public PaperCard getCard(String n, String e, int a) { return null; }
    @Override public PaperCard getCard(String n, String e, String c) { return null; }
    @Override public PaperCard getCard(String n, String e, int a, Map<String, String> f) { return null; }
    @Override public PaperCard getCard(String n, String e, String c, Map<String, String> f) { return null; }
    @Override public PaperCard getCardFromSet(String n, CardEdition e, boolean f) { return null; }
    @Override public PaperCard getCardFromSet(String n, CardEdition e, String c, boolean f) { return null; }
    @Override public PaperCard getCardFromSet(String n, CardEdition e, int a, boolean f) { return null; }
    @Override public PaperCard getCardFromSet(String n, CardEdition e, int a, String c, boolean f) { return null; }
    @Override public PaperCard getCardFromEditions(String n, CardArtPreference p) { return null; }
    @Override public PaperCard getCardFromEditions(String n, CardArtPreference p, Predicate<PaperCard> f) { return null; }
    @Override public PaperCard getCardFromEditions(String n, CardArtPreference p, int a) { return null; }
    @Override public PaperCard getCardFromEditions(String n, CardArtPreference p, int a, Predicate<PaperCard> f) { return null; }
    @Override public PaperCard getCardFromEditionsReleasedBefore(String n, CardArtPreference p, Date d) { return null; }
    @Override public PaperCard getCardFromEditionsReleasedBefore(String n, CardArtPreference p, Date d, Predicate<PaperCard> f) { return null; }
    @Override public PaperCard getCardFromEditionsReleasedBefore(String n, CardArtPreference p, int a, Date d) { return null; }
    @Override public PaperCard getCardFromEditionsReleasedBefore(String n, CardArtPreference p, int a, Date d, Predicate<PaperCard> f) { return null; }
    @Override public PaperCard getCardFromEditionsReleasedAfter(String n, CardArtPreference p, Date d) { return null; }
    @Override public PaperCard getCardFromEditionsReleasedAfter(String n, CardArtPreference p, Date d, Predicate<PaperCard> f) { return null; }
    @Override public PaperCard getCardFromEditionsReleasedAfter(String n, CardArtPreference p, int a, Date d) { return null; }
    @Override public PaperCard getCardFromEditionsReleasedAfter(String n, CardArtPreference p, int a, Date d, Predicate<PaperCard> f) { return null; }
    @Override public Collection<PaperCard> getAllCards() { return Collections.emptyList(); }
    @Override public List<PaperCard> getAllCards(String n) { return Collections.emptyList(); }
    @Override public List<PaperCard> getAllCards(Predicate<PaperCard> p) { return Collections.emptyList(); }
    @Override public List<PaperCard> getAllCards(String n, Predicate<PaperCard> p) { return Collections.emptyList(); }
    @Override public Collection<PaperCard> getAllCards(CardEdition e) { return Collections.emptyList(); }
    @Override public Collection<PaperCard> getUniqueCards() { return Collections.emptyList(); }
    @Override public Stream<PaperCard> streamAllCards() { return Stream.empty(); }
    @Override public Stream<PaperCard> streamUniqueCards() { return Stream.empty(); }
    @Override public int getMaxArtIndex(String n) { return 1; }
    @Override public int getArtCount(String n, String e) { return 0; }
    @Override public Predicate<? super PaperCard> wasPrintedInSets(Collection<String> s) { return p -> false; }
    @Override public Predicate<? super PaperCard> isLegal(Collection<String> s) { return p -> false; }
    @Override public Predicate<? super PaperCard> wasPrintedAtRarity(CardRarity r) { return p -> false; }

    // ── IDeckGenPool ─────────────────────────────────────────────────────────

    @Override public boolean contains(String n) { return false; }

    // ── Extra public methods referenced by FModel / other callers ────────────

    public CardArtPreference getCardArtPreference() { return CardArtPreference.LATEST_ART_ALL_EDITIONS; }
    public void setCardArtPreference(boolean latestArt, boolean coreExpansionOnly) { }
    public void setCardArtPreference(String artPreference) { }
    public void initialize(boolean logMissing, boolean logSummary, boolean enableUnknown) { }
    public void loadCard(String cardName, String setCode, CardRules cr) { }
    public void addCard(PaperCard paperCard) { }
    public Collection<PaperCard> getUniqueCardsNoAlt() { return Collections.emptyList(); }
    public List<PaperCard> getUniqueCardsNoAlt(String cardName) { return Collections.emptyList(); }
    public Collection<PaperCard> getAllCardsNoAlt() { return Collections.emptyList(); }
    public List<PaperCard> getAllCardsNoAlt(String cardName) { return Collections.emptyList(); }
    public List<PaperCard> getAllCardsNoAlt(Predicate<PaperCard> predicate) { return Collections.emptyList(); }
    public boolean hasPreferredArt(String cardName) { return false; }
    public boolean setPreferredArt(String cardName, String setCode, int artIndex) { return false; }
    public CardRules getRules(String cardName) { return null; }
    public PaperCard getCardFromEditions(String cardName) { return null; }
    public PaperCard getUniqueByName(String cardName) { return null; }
    public ICardFace getFaceByName(String name) { return null; }
    public Collection<ICardFace> getAllFaces() { return Collections.emptyList(); }
    public Stream<ICardFace> streamAllFaces() { return Stream.empty(); }
    public Collection<PaperCard> getAllNonPromosNonReprintsNoAlt() { return Collections.emptyList(); }
    public int getArtCount(String cardName, String setCode, String collectorNumber) { return 0; }
    public String getNormalizedName(String cardName) { return cardName; }
    public boolean isNonLegendaryCreatureName(String name) { return false; }
    public PaperCard createUnsupportedCard(String cardName) { return null; }

    /** Field referenced by Deck.isCardArtUpdateRequired */
    public static final String EDITION_NON_PROMO = "EDITION_NON_PROMO";
}
