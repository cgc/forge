package forge.gui.events;

import forge.gamemodes.match.NextGameDecision;
import forge.player.PlayerControllerHuman;

import java.util.Objects;

public final class UiEventNextGameDecision implements UiEvent {
    private final PlayerControllerHuman controller;
    private final NextGameDecision decision;

    public UiEventNextGameDecision(PlayerControllerHuman controller, NextGameDecision decision) {
        this.controller = controller;
        this.decision = decision;
    }

    public PlayerControllerHuman controller() {
        return controller;
    }

    public NextGameDecision decision() {
        return decision;
    }

    @Override
    public <T> T visit(IUiEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UiEventNextGameDecision)) return false;
        UiEventNextGameDecision that = (UiEventNextGameDecision) o;
        return Objects.equals(controller, that.controller) && Objects.equals(decision, that.decision);
    }

    @Override
    public int hashCode() {
        return Objects.hash(controller, decision);
    }

    @Override
    public String toString() {
        return "UiEventNextGameDecision[controller=" + controller + ", decision=" + decision + "]";
    }

}
