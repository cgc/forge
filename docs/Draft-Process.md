# How Drafting Works in Forge

This document is aimed at new contributors and curious players who want to understand how Forge's booster-draft mode works under the hood — in particular, how AI opponents choose their cards during the draft, and how decks are chosen when you play your matches afterward.

---

## Table of Contents

1. [Overview of a Booster Draft](#overview-of-a-booster-draft)
2. [Draft Formats Available](#draft-formats-available)
3. [The Draft Loop](#the-draft-loop)
4. [How AIs Choose Their Cards](#how-ais-choose-their-cards)
   - [Step 1 — Raw Score from Rankings](#step-1--raw-score-from-rankings)
   - [Step 2 — Color Commitment](#step-2--color-commitment)
   - [Step 3 — Synergy Bonus and Deck-Need Penalty](#step-3--synergy-bonus-and-deck-need-penalty)
   - [Putting It Together](#putting-it-together)
   - [Conspiracy Card Interactions](#conspiracy-card-interactions)
5. [Building the Deck After the Draft](#building-the-deck-after-the-draft)
6. [What Decks Are Selected to Play Against](#what-decks-are-selected-to-play-against)
7. [Key Source Files](#key-source-files)

---

## Overview of a Booster Draft

A booster draft is a limited-format game mode in which all players at the table build their decks on-the-fly from booster packs, rather than bringing a pre-constructed deck.

The classic structure is:

1. Eight players sit in a circle (the *pod*).
2. Each player opens a booster pack, privately looks at all the cards, picks **one** card, and passes the remaining cards to the neighbor on their left.
3. Each player then picks one card from the pack they just received and passes the rest again. This continues until every card in the first pack has been taken.
4. A second pack is opened and the same process repeats, but cards are passed to the **right**.
5. A third pack repeats the leftward pass.
6. After all three packs are exhausted every player has drafted roughly 42–45 cards, from which they build a **40-card minimum deck** (usually 22–23 non-land cards plus 17–18 basic lands).

In Forge the human player makes picks through the UI, while the other seven seats are controlled by AI opponents.

---

## Draft Formats Available

When you start a new booster draft in Forge you can choose from several formats, each of which changes which packs end up on the table:

| Format | What you draft from |
|---|---|
| **Block / Set** | Boosters from a specific Magic set or block (e.g. three *Khans of Tarkir* packs). |
| **Full** | One booster drawn from *every* card in Forge — no set restriction. |
| **Chaos** | Each pack is opened from a randomly chosen set. You can further restrict which sets are eligible by choosing a theme (e.g. *Modern-legal only*, *Zendikar block*, etc.). Theme definitions live in `forge-gui/res/`. |
| **Custom / Cube** | A user-defined card list (a *cube*). Forge ships with dozens of pre-built cubes (MTGO Legacy Cube, MTGO Vintage Cube, community-designed cubes, …) stored as `.draft` files in `forge-gui/res/draft/`. |
| **CubeCobra Import** | Fetches a cube list live from cubecobra.com and creates a temporary custom draft session from it. |

---

## The Draft Loop

Once packs are generated, `BoosterDraft.nextChoice()` drives the pick-by-pick loop:

1. If the current round is over, the next round is started (`startRound()`), which hands each player a fresh pack from their stack of unopened product.
2. All AI players immediately make their picks for the current pack (via `computerChoose()`, which calls `LimitedPlayerAI.chooseCard()` on each AI in seating order).
3. The human player's pack is presented to the UI. The human picks one card.
4. All remaining packs are passed to the appropriate neighbor (`passPacks()`), and the cycle repeats.

Packs pass **left** in odd-numbered rounds (round 1, 3) and **right** in even-numbered rounds (round 2).

---

## How AIs Choose Their Cards

Every AI player is an instance of `LimitedPlayerAI`. When it is time to pick, `chooseCard()` scores every card remaining in the current pack and returns the highest-scoring one.

The score has three components.

### Step 1 — Raw Score from Rankings

Forge ships with **per-set draft ranking files** stored in:

```
forge-gui/res/draft/rankings/<setcode>.rnk
```

Each line looks like:
```
#1|Razormane Masticore|R|10E
#2|Platinum Angel|R|10E
```

The rank number reflects how highly the card should be picked relative to the rest of the set (lower rank = better). `ReadDraftRankings` loads these files at startup and stores them in `DraftRankCache`.

At pick time, `CardRanker.getRawScore()` converts the stored rank to a 0–100 score with this formula:

```
rawScore = 100 - (100 × (cardRank / setSize))
```

So the best card in the set scores close to **100**, a median card scores around **50**, and the worst card scores near **0**.

Special cases:
- **Basic lands** are hardcoded to **−100** so the AI never wastes a pick on them.
- **Cards with no ranking** (the set has no `.rnk` file, or the card simply isn't listed) also receive **−100**.

If a **custom rankings file** is active (used by cube drafts that ship their own ranking list), it takes priority. Cards absent from the custom file fall back to the default rankings, but with a **+1 rank penalty** before the calculation, so custom-file entries always outrank fallbacks.

---

### Step 2 — Color Commitment

Each AI tracks its chosen colors in a `DeckColors` object, which allows a maximum of **two colors** by default.

- After picking a colored card the AI calls `DeckColors.addColorsOf()`, which adds that card's colors to the running total — but only up to the two-color limit.
- Once both color slots are filled (`canChoseMoreColors()` returns `false`), any card whose color identity does not fit those two colors receives a **−50 penalty**.

This heavy penalty ensures the AI commits early and does not accidentally draft a three-color pile.

> **Example:** An AI has committed to White/Blue. A Red removal spell might have a raw score of 70, but after the −50 penalty it scores only 20, which is likely worse than a mediocre on-color card at 25.

---

### Step 3 — Synergy Bonus and Deck-Need Penalty

`CardRanker.getScoreForDeckHints()` compares the card being evaluated against every card already in the AI's current draft pool (its sideboard) to model synergy.

**Synergy Bonus — `DeckHints`**

Many cards in Forge carry a `DeckHints` annotation in their card data. This annotation says, in effect, "I work well alongside cards that match this criterion." For every card in the AI's pool that matches the hint of the card being scored, a bonus is added:

| Hint type | Bonus per matching card already in pool |
|---|---|
| `NAME` | +10 |
| `ABILITY` | +3 |
| `KEYWORD` | +3 |
| `TYPE` | +3 |
| `COLOR` | +1 |

**Deck-Need Penalty — `DeckNeeds`**

Some cards also carry a `DeckNeeds` annotation — a requirement that says "I need a certain number of cards matching this criterion to be viable." If those supporting cards are not yet in the pool, a fractional penalty is applied:

```
penalty = −factor × (threshold − matchCount) / threshold
```

Where `threshold` is the minimum number of support cards needed and `factor` is the type-specific bonus value from the table above. As the AI drafts more support cards the penalty shrinks toward zero.

> **Example:** A card with `DeckNeeds:TYPE.Zombie` with a threshold of 8 and 0 matching cards already drafted would receive a penalty of `−3 × (8−0)/8 = −3.0`. After drafting 4 Zombies the penalty shrinks to `−3 × (8−4)/8 = −1.5`.

**Cards flagged `RemAIDecks`** receive an extra **−20** penalty regardless of other scores, ensuring the AI treats them as near-unplayable.

---

### Putting It Together

The final score for a card during a pick is:

```
finalScore = rawScore
           − 20  (if RemAIDecks)
           − 50  (if off-color and colors are locked in)
           + synergyBonus  (from DeckHints of cards in pool)
           − deckNeedPenalty  (from DeckNeeds of the card itself)
```

The card with the **highest `finalScore`** is always selected. There is no randomization in the pick decision itself.

---

### Conspiracy Card Interactions

For sets that include **Conspiracy** cards (e.g. *Conspiracy*, *Conspiracy: Take the Crown*, and *Paliano*), drafting has extra actions — revealing cards, noting names, peeking at other players' packs, and so on. `LimitedPlayer` handles these via a bitmask of player flags (e.g. `NobleBanneretActive`, `SmugglerCaptainActive`, `WhispergearBoosterPeek`).

The AI overrides the decision methods for each of these cards in `LimitedPlayerAI`, but most implementations are simple heuristics (e.g. always reveal the first creature not yet noted). A few are stubs that could be improved (`Animus of Predation`, `Cogwork Grinder`).

---

## Building the Deck After the Draft

Every card an AI picks goes into a special `DeckSection.Sideboard` within its `Deck` object — this is the *full drafted pool*, not a final deck. Once the draft is complete, `BoosterDraft.getComputerDecks()` calls `LimitedPlayerAI.buildDeck()` for each AI player.

`buildDeck()` delegates to `BoosterDeckBuilder`, which extends `LimitedDeckBuilder`. The algorithm:

1. **Filter by color** — only cards that fit the AI's two chosen colors (plus colorless cards) are considered playable.
2. **Remove unplayables** — cards flagged `RemAIDecks` are excluded from the main deck.
3. **Fill the creature curve** — creatures are added in order of mana cost, targeting approximately 16 creatures that fit a standard limited mana curve.
4. **Add non-creature spells** — on-color instants, sorceries, enchantments, and planeswalkers fill the remaining spell slots, up to a total of ~22 non-land cards.
5. **Add non-basic lands** — any dual lands or utility lands that were drafted and fit the color identity are added.
6. **Handle Conspiracy cards** — any drafted conspiracies are added to a dedicated deck section.
7. **Fill with basic lands** — remaining slots (typically 17–18) are filled with Basic Lands sourced from the set designated as the land set for that draft.

The result is a 40-card minimum deck. All unused drafted cards remain in the sideboard.

---

## What Decks Are Selected to Play Against

### After the Draft Ends

Immediately after the draft, the UI controller (`CEditorDraftingProcess`) calls `BoosterDraft.getComputerDecks()` to build all AI decks. These decks are bundled with the human's deck into a `DeckGroup` object and saved to disk under the human player's chosen draft name. The AI decks are stored in the `DeckGroup`'s AI deck list, in the same order as the players' seating positions in the pod (AI player 1 is index 0, AI player 2 is index 1, …).

### When You Start Your Matches

When you click "Play" in the draft results screen, `GauntletMini.launch()` is called. It loads the saved AI decks for your draft, then selects opponents according to how many rounds you want to play:

- **Single-round play** — one AI deck is selected **at random** from the full list of saved AI decks. This is intended for quick one-off games against any opponent.

- **Multi-round gauntlet** — opponents are assigned in **seating order** starting from AI player 1 (index 0). If you request 3 rounds you will face AI players 1, 2, and 3 in that order. The number of rounds is capped at the number of available AI decks so you never face the same opponent twice.

During each round, `GauntletMini.startRound()` creates a standard match between the human deck and the next AI deck in the list. Wins and losses are tracked across rounds via `addWin()` / `addLoss()`.

---

## Key Source Files

| File | What it does |
|---|---|
| `forge-gui/…/limited/BoosterDraft.java` | Orchestrates the full draft loop; handles pack generation, passing, and calling AI picks. |
| `forge-gui/…/limited/LimitedPlayerAI.java` | AI player; implements `chooseCard()` and `buildDeck()`. |
| `forge-gui/…/limited/CardRanker.java` | Scores cards using raw rankings + synergy bonuses/penalties. |
| `forge-gui/…/limited/DraftRankCache.java` | In-memory cache of all loaded `.rnk` ranking files. |
| `forge-gui/…/limited/ReadDraftRankings.java` | Parses `.rnk` files from `res/draft/rankings/`. |
| `forge-gui/…/limited/DeckColors.java` | Tracks the AI's committed colors during the draft. |
| `forge-gui/…/limited/LimitedDeckBuilder.java` | Base class for constructing a 40-card limited deck. |
| `forge-gui/…/limited/BoosterDeckBuilder.java` | Booster-draft-specific deck builder; extends `LimitedDeckBuilder`. |
| `forge-gui/…/limited/GauntletMini.java` | Manages the post-draft gauntlet; selects and sequences AI opponents. |
| `forge-gui-desktop/…/CEditorDraftingProcess.java` | Desktop UI controller; drives the draft screen and triggers deck saving when the draft ends. |
| `forge-gui/res/draft/rankings/` | Per-set draft ranking files (one `.rnk` file per Magic set). |
| `forge-gui/res/draft/` | Custom cube / draft definition files (`.draft`). |
