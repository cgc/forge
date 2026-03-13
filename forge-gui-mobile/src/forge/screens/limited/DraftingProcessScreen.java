package forge.screens.limited;

import forge.Forge;
import org.apache.commons.lang3.StringUtils;

import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.deck.FDeckEditor;
import forge.deck.io.DeckPreferences;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.DraftRankCache;
import forge.gamemodes.quest.QuestEventDraft;
import forge.gamemodes.quest.QuestTournamentController;
import forge.gui.FThreads;
import forge.model.FModel;
import forge.screens.home.LoadGameMenu.LoadGameScreen;
import forge.toolbox.FOptionPane;

import java.util.function.Consumer;

public class DraftingProcessScreen extends FDeckEditor {
    private boolean isDraftSaved;
    private final BoosterDraft draft;
    private final QuestTournamentController questDraftController;
    protected FDraftLog draftLog;

    /**
     * Logs current Java heap and (on iOS) Mach physical footprint via
     * {@link DraftRankCache#logHeap}.  Output is visible on iOS in Console.app
     * and on desktop in the terminal.
     *
     * <p>On iOS, {@code logHeap} also emits a {@code phys=} line using the Mach
     * {@code task_info} physical-footprint value set up in {@code Main}; this is
     * the value jetsam monitors and is far more diagnostic than the Java heap
     * alone.  The previous {@code native=} line (which called
     * {@code Gdx.app.getNativeHeap()}) has been removed: on iOS that method
     * delegates to {@code getJavaHeap()}, making it a duplicate of {@code used=}
     * rather than a measurement of GPU or native memory.
     */
    private static void logMemory(String tag) {
        DraftRankCache.logHeap(tag);
    }

    public DraftingProcessScreen(BoosterDraft draft, DeckEditorConfig editorConfig) {
        this(draft, editorConfig, null);
    }

    public DraftingProcessScreen(BoosterDraft draft, DeckEditorConfig editorConfig, QuestTournamentController questDraftController) {
        super(editorConfig, draft.getDecksAsGroup());
        this.draft = draft;
        this.questDraftController = questDraftController;
        getCatalogPage().scheduleRefresh(); //must refresh after draft set

        if(draft.shouldShowDraftLog()) {
            this.draftLog = new FDraftLog();
            draft.setLogEntry(this.draftLog);
            deckHeader.initDraftLog(this.draftLog, this);
        }
        logMemory("draft-start");
    }

    @Override
    public BoosterDraft getDraft() {
        return draft;
    }

    @Override
    public boolean isDrafting() {
        return !isDraftSaved;
    }

    protected boolean isQuestDraft() {
        return questDraftController != null;
    }

    @Override
    public void save(final Consumer<Boolean> callback) {
        if (isDraftSaved) { //if draft itself is saved, let base class handle saving deck changes
            super.save(callback);
            return;
        }

        if (isQuestDraft()) {
            finishSave(QuestEventDraft.DECK_NAME);
            if (callback != null) {
                callback.accept(true);
            }
            return;
        }

        FThreads.invokeInEdtNowOrLater(() -> {
            FOptionPane.showInputDialog(Forge.getLocalizer().getMessage("lblSaveDraftAs") + "?", name -> {
                if (StringUtils.isEmpty(name)) {
                    save(callback); //re-prompt if user doesn't pick a name
                    return;
                }

                // Check for overwrite case
                for (DeckGroup d : FModel.getDecks().getDraft()) {
                    if (name.equalsIgnoreCase(d.getName())) {
                        FOptionPane.showConfirmDialog(
                                Forge.getLocalizer().getMessage("lblAlreadyDeckName") + name + Forge.getLocalizer().getMessage("lblOverwriteConfirm"),
                                Forge.getLocalizer().getMessage("lblOverwriteDeck"), false, result -> {
                                    if (result) {
                                        finishSave(name);
                                        if (callback != null) {
                                            callback.accept(true);
                                        }
                                    } else {
                                        save(callback); //If no overwrite, recurse
                                    }
                                });
                        return;
                    }
                }

                finishSave(name);
                if (callback != null) {
                    callback.accept(true);
                }
            });
        });
    }

    private void finishSave(String name) {
        isDraftSaved = true;

        // Construct computer's decks and save draft
        final Deck[] computer = draft.getComputerDecks();

        final DeckGroup finishedDraft = new DeckGroup(name);
        finishedDraft.setHumanDeck((Deck) getDeck().copyTo(name));
        finishedDraft.addAiDecks(computer);

        if(!isQuestDraft()) {
            FModel.getDecks().getDraft().add(finishedDraft);
            FDeckEditor.DECK_CONTROLLER_DRAFT.load("", name);
            DeckPreferences.setDraftDeck(name);

            LoadGameScreen.BoosterDraft.setAsBackScreen(false); //set load draft screen to be opened when user done editing deck
            LoadGameScreen.BoosterDraft.open();
        }
        else {
            FModel.getQuest().getDraftDecks().add(finishedDraft);
            FDeckEditor.DECK_CONTROLLER_QUEST_DRAFT.load("", name);
        }

        // Release per-edition draft ranking data that was loaded lazily during AI evaluation.
        // Rankings are only needed while AI players are picking; after the draft is saved the
        // data is no longer used and can be GC'd to reduce heap pressure on iOS.
        logMemory("pre-clear");
        DraftRankCache.clear();
        System.gc(); // hint: reclaim freed draft-ranking data promptly (effective with Boehm GC on iOS)
        logMemory("post-gc");

        //show header for main deck and sideboard when finished drafting
        deckHeader.setVisible(true);
        revalidate();
    }

    @Override
    public void onClose(final Consumer<Boolean> canCloseCallback) {
        if (isDraftSaved || canCloseCallback == null) {
            super.onClose(canCloseCallback); //can skip prompt if draft saved
            return;
        }

        if (isQuestDraft()) {
            FThreads.invokeInBackgroundThread(() -> {
                if (questDraftController.cancelDraft()) {
                    logMemory("abandon-pre-clear");
                    DraftRankCache.clear(); // release ranking data on draft abandonment
                    System.gc(); // hint: reclaim freed draft-ranking data promptly
                    FThreads.invokeInEdtLater(() -> canCloseCallback.accept(true));
                }
            });
            return;
        }

        FOptionPane.showConfirmDialog(Forge.getLocalizer().getMessage("lblEndDraftConfirm"), Forge.getLocalizer().getMessage("lblLeaveDraft"), Forge.getLocalizer().getMessage("lblLeave"), Forge.getLocalizer().getMessage("lblCancel"), false, result -> {
            if (Boolean.TRUE.equals(result)) {
                logMemory("abandon-pre-clear");
                DraftRankCache.clear(); // release ranking data on draft abandonment
                System.gc(); // hint: reclaim freed draft-ranking data promptly
            }
            canCloseCallback.accept(result);
        });
    }
}
