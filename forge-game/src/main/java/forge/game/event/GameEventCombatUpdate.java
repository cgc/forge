package forge.game.event;

import java.util.List;
import java.util.stream.Collectors;

import forge.game.card.Card;
import forge.game.card.CardView;
import forge.util.StreamUtil;

public record GameEventCombatUpdate(List<CardView> attackers, List<CardView> blockers) implements GameEvent {

    public static GameEventCombatUpdate fromCards(List<Card> attackers, List<Card> blockers) {
        return new GameEventCombatUpdate(
            attackers == null ? null : StreamUtil.stream(attackers).map(CardView::get).collect(Collectors.toList()),
            blockers == null ? null : StreamUtil.stream(blockers).map(CardView::get).collect(Collectors.toList())
        );
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

}
