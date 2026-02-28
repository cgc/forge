package forge.game.event;

import java.util.List;
import java.util.stream.Collectors;

import forge.game.card.Card;
import forge.game.card.CardView;
import forge.util.StreamUtil;

public record GameEventCombatEnded(List<CardView> attackers, List<CardView> blockers) implements GameEvent {

    public static GameEventCombatEnded fromCards(List<Card> attackers, List<Card> blockers) {
        return new GameEventCombatEnded(
            attackers == null ? null : StreamUtil.stream(attackers).map(CardView::get).collect(Collectors.toList()),
            blockers == null ? null : StreamUtil.stream(blockers).map(CardView::get).collect(Collectors.toList())
        );
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "Combat ended. Attackers: " + attackers + " Blockers: " + blockers;
    }
}
