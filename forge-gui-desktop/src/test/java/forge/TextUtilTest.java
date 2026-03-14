package forge;

import forge.util.TextUtil;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

/**
 * Correctness tests for {@link TextUtil#stripAccents(String)} and
 * {@link TextUtil#toSortableName(String)}.
 *
 * The thread-local-Matcher optimisation must produce results identical to the
 * original implementations ({@code String.replaceAll} / {@code StringUtils.stripAccents}).
 */
@Test(groups = { "UnitTest" })
public class TextUtilTest {

    // ---- stripAccents -----------------------------------------------------------------

    @Test(groups = { "UnitTest", "fast" })
    public void stripAccents_null_returnsNull() {
        assertNull(TextUtil.stripAccents(null));
    }

    @Test(groups = { "UnitTest", "fast" })
    public void stripAccents_ascii_unchanged() {
        assertEquals(TextUtil.stripAccents("normal"), "normal");
    }

    @Test(groups = { "UnitTest", "fast" })
    public void stripAccents_precomposedAccents() {
        // NFD decomposition handles these (é → e + combining-acute, removed by pattern)
        assertEquals(TextUtil.stripAccents("café"),   "cafe");
        assertEquals(TextUtil.stripAccents("naïve"),  "naive");
        assertEquals(TextUtil.stripAccents("über"),   "uber");
        assertEquals(TextUtil.stripAccents("Márton"), "Marton");
        assertEquals(TextUtil.stripAccents("résumé"), "resume");
        assertEquals(TextUtil.stripAccents("Ångström"), "Angstrom");
        assertEquals(TextUtil.stripAccents("Hülsbeck"), "Hulsbeck");
    }

    @Test(groups = { "UnitTest", "fast" })
    public void stripAccents_specialNonDecomposable_Lslash() {
        // Ł/ł do not decompose under NFD; handled via explicit replacement (matches
        // StringUtils.convertRemainingAccentCharacters in commons-lang 3.18.0)
        assertEquals(TextUtil.stripAccents("Łódź"), "Lodz");
    }

    @Test(groups = { "UnitTest", "fast" })
    public void stripAccents_charactersNotHandledByCommonsLang3_18() {
        // In commons-lang 3.18.0 Æ, Ø and ß are NOT converted — verify we match that.
        assertEquals(TextUtil.stripAccents("Æther"), "Æther");
        assertEquals(TextUtil.stripAccents("Søren"), "Søren");
        assertEquals(TextUtil.stripAccents("ße"),    "ße");
    }

    // ---- toSortableName ---------------------------------------------------------------

    @Test(groups = { "UnitTest", "fast" })
    public void toSortableName_article_movedToEnd() {
        assertEquals(TextUtil.toSortableName("The Hive"),   "hive the");
        assertEquals(TextUtil.toSortableName("An Inn"),     "inn an");
        assertEquals(TextUtil.toSortableName("A Forest"),   "forest a");
    }

    @Test(groups = { "UnitTest", "fast" })
    public void toSortableName_noArticle_unchanged() {
        assertEquals(TextUtil.toSortableName("Arcane Denial"), "arcane denial");
        assertEquals(TextUtil.toSortableName("Fire"),           "fire");
    }

    @Test(groups = { "UnitTest", "fast" })
    public void toSortableName_nonAsciiStripped() {
        // Non-ASCII chars (including Æ after toLowerCase → æ) are removed
        assertEquals(TextUtil.toSortableName("Æther Vial"),      "ther vial");
        assertEquals(TextUtil.toSortableName("Márton Stromgald"), "mrton stromgald");
        assertEquals(TextUtil.toSortableName("Jötun Grunt"),      "jtun grunt");
    }

    @Test(groups = { "UnitTest", "fast" })
    public void toSortableName_apostropheKept() {
        assertEquals(TextUtil.toSortableName("D'Avenant Healer"), "d'avenant healer");
        assertEquals(TextUtil.toSortableName("Lim-Dul's Vault"),  "limdul's vault");
    }

    @Test(groups = { "UnitTest", "fast" })
    public void toSortableName_leadingQuoteStripped() {
        assertEquals(TextUtil.toSortableName("\"Fire // Ice\""), "fire  ice");
    }

    @Test(groups = { "UnitTest", "fast" })
    public void toSortableName_punctuationStripped() {
        assertEquals(TextUtil.toSortableName("Ach! Hans, Run!"), "ach hans run");
    }
}
