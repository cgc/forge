package forge.gui.events;

import forge.game.card.CardView;

import java.util.Objects;

public final class UiEventBlockerAssigned implements UiEvent {
    private final CardView blocker;
    private final CardView attackerBeingBlocked;

    public UiEventBlockerAssigned(CardView blocker, CardView attackerBeingBlocked) {
        this.blocker = blocker;
        this.attackerBeingBlocked = attackerBeingBlocked;
    }

    public CardView blocker() {
        return blocker;
    }

    public CardView attackerBeingBlocked() {
        return attackerBeingBlocked;
    }

    @Override
    public <T> T visit(final IUiEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UiEventBlockerAssigned)) return false;
        UiEventBlockerAssigned that = (UiEventBlockerAssigned) o;
        return Objects.equals(blocker, that.blocker) && Objects.equals(attackerBeingBlocked, that.attackerBeingBlocked);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blocker, attackerBeingBlocked);
    }

    @Override
    public String toString() {
        return "UiEventBlockerAssigned[blocker=" + blocker + ", attackerBeingBlocked=" + attackerBeingBlocked + "]";
    }
}