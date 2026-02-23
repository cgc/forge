package forge.gui.events;

import forge.game.GameEntityView;
import forge.game.card.CardView;

import java.util.Objects;

public final class UiEventAttackerDeclared implements UiEvent {
    private final CardView attacker;
    private final GameEntityView defender;

    public UiEventAttackerDeclared(CardView attacker, GameEntityView defender) {
        this.attacker = attacker;
        this.defender = defender;
    }

    public CardView attacker() {
        return attacker;
    }

    public GameEntityView defender() {
        return defender;
    }

    @Override
    public <T> T visit(final IUiEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return attacker.toString() + ( defender == null ? " removed from combat" : " declared to attack " + defender ); 
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UiEventAttackerDeclared)) return false;
        UiEventAttackerDeclared that = (UiEventAttackerDeclared) o;
        return Objects.equals(attacker, that.attacker) && Objects.equals(defender, that.defender);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attacker, defender);
    }
}
