package forge.localinstance.achievements;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Maps;

import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.gui.GuiBase;
import forge.gui.interfaces.IComboBox;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import forge.util.FileUtil;
import forge.util.Localizer;
import forge.util.ThreadUtil;

/**
 * Web-target stub for {@code forge.localinstance.achievements.AchievementCollection}.
 *
 * <p>The real {@code load()} and {@code save()} methods use
 * {@code javax.xml.parsers.DocumentBuilderFactory} and {@code org.w3c.dom.*},
 * which are absent from TeaVM's JS class library.  This stub replaces those
 * methods with no-ops so the XML classes are never referenced in the live
 * dependency graph.
 */
public abstract class AchievementCollection implements Iterable<Achievement> {
    protected final Map<String, Achievement> achievements = Maps.newLinkedHashMap();
    protected final String name, filename, path;
    protected final boolean isLimitedFormat;

    static {
        FileUtil.ensureDirectoryExists(ForgeConstants.ACHIEVEMENTS_DIR);
    }

    public static void updateAll(final PlayerControllerHuman controller) {
        if (controller.hasCheated()) { return; }
        final Match match = controller.getMatch();
        final Player player = controller.getPlayer();
        if (GuiBase.getInterface().isLibgdxPort()) {
            ThreadUtil.invokeInGameThread(() -> doUpdateAllAchievements(match, player));
        } else {
            doUpdateAllAchievements(match, player);
        }
    }

    private static void doUpdateAllAchievements(final Match match, final Player player) {
        FModel.getAchievements(match.getRules().getGameType()).updateAll(player);
        AltWinAchievements.instance.updateAll(player);
        PlaneswalkerAchievements.instance.updateAll(player);
        ChallengeAchievements.instance.updateAll(player);
        CardActivationAchievements.instance.updateAll(player);
    }

    public static void buildComboBox(IComboBox<AchievementCollection> cb) {
        cb.addItem(FModel.getAchievements(GameType.Constructed));
        cb.addItem(FModel.getAchievements(GameType.Draft));
        cb.addItem(FModel.getAchievements(GameType.Sealed));
        cb.addItem(FModel.getAchievements(GameType.Quest));
        cb.addItem(FModel.getAchievements(GameType.PlanarConquest));
        cb.addItem(FModel.getAchievements(GameType.Puzzle));
        cb.addItem(FModel.getAchievements(GameType.Adventure));
        cb.addItem(AltWinAchievements.instance);
        cb.addItem(PlaneswalkerAchievements.instance);
        cb.addItem(CardActivationAchievements.instance);
        cb.addItem(ChallengeAchievements.instance);
    }

    protected AchievementCollection(String name0, String filename0, boolean isLimitedFormat0) {
        this(name0, filename0, isLimitedFormat0, null);
    }

    protected AchievementCollection(String name0, String filename0, boolean isLimitedFormat0, String path0) {
        name = Localizer.getInstance().getMessage(name0);
        filename = filename0;
        isLimitedFormat = isLimitedFormat0;
        path = path0;
        addSharedAchievements();
        addAchievements();
        load();
    }

    protected void addSharedAchievements() {
        add(new GameWinStreak(10, 25, 50, 100));
        add(new MatchWinStreak(10, 25, 50, 100));
        add(new TotalGameWins(250, 500, 1000, 2000));
        add(new TotalMatchWins(100, 250, 500, 1000));
        if (isLimitedFormat) {
            add(new NeedForSpeed(8, 6, 4, 2));
        } else {
            add(new NeedForSpeed(5, 3, 1, 0));
        }
        add(new Overkill(-25, -50, -100, -200));
        add(new LifeToSpare(20, 40, 80, 160));
        add(new Hellbent());
        add(new ArcaneMaster());
        add(new StormChaser(5, 10, 20, 50));
        add(new ManaScrewed());
        if (isLimitedFormat) {
            add(new ManaFlooded(8, 11, 14, 17));
        } else {
            add(new ManaFlooded(8, 12, 18, 24));
        }
        add(new RagsToRiches());
    }

    protected void addAchievements() {
        if (path != null) {
            final List<String> achievementListFile = FileUtil.readFile(path);
            for (final String s : achievementListFile) {
                if (!s.isEmpty()) {
                    String[] k = StringUtils.split(s, "|");
                    add(k[0], k[1], k[2]);
                }
            }
        }
    }

    protected void add(Achievement achievement) {
        achievements.put(achievement.getKey(), achievement);
    }

    protected void add(String name, String title, String desc) {
    }

    public void updateAll(Player player) {
        for (Achievement achievement : achievements.values()) {
            achievement.update(player);
        }
        save();
    }

    /** No-op on web target — achievements are not persisted to the filesystem. */
    public void load() {
    }

    /** No-op on web target — achievements are not persisted to the filesystem. */
    protected void save() {
    }

    public int getCount() {
        return achievements.size();
    }

    @Override
    public Iterator<Achievement> iterator() {
        return achievements.values().iterator();
    }

    @Override
    public String toString() {
        return name;
    }
}
