package forge.game.card;

import forge.game.CardTraitBase;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.Trigger;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public record CardTraitChanges(
        Collection<SpellAbility> abilities,
        Collection<SpellAbility> removedAbilities,
        Collection<Trigger> triggers,
        Collection<ReplacementEffect> replacements,
        Collection<StaticAbility> staticAbilities,
        Predicate<CardTraitBase> remove
        ) implements ICardTraitChanges {

    /**
     * @return the abilities
     */
    public Collection<SpellAbility> getAbilities() {
        return abilities != null ? abilities : Collections.emptyList();
    }

    /**
     * @return the abilities
     */
    public Collection<SpellAbility> getRemovedAbilities() {
        return removedAbilities != null ? removedAbilities : Collections.emptyList();
    }

    /**
     * @return the triggers
     */
    public Collection<Trigger> getTriggers() {
        return triggers != null ? triggers : Collections.emptyList();
    }
    /**
     * @return the replacements
     */
    public Collection<ReplacementEffect> getReplacements() {
        return replacements != null ? replacements : Collections.emptyList();
    }

    /**
     * @return the staticAbilities
     */
    public Collection<StaticAbility> getStaticAbilities() {
        return staticAbilities != null ? staticAbilities : Collections.emptyList();
    }

    /**
     * Return if any of the static abilities changes the card's mana cost
     */
    public boolean containsCostChange() {
        for (StaticAbility stAb : getStaticAbilities()) {
            if (stAb.checkMode(StaticAbilityMode.ReduceCost) || stAb.checkMode(StaticAbilityMode.RaiseCost)) {
                return true;
            }
        }
        return false;
    }

    public CardTraitChanges copy(Card host, boolean lki) {
        return new CardTraitChanges(
                this.getAbilities().stream().map(sa -> sa.copy(host, lki)).collect(Collectors.toList()),
                this.getRemovedAbilities().stream().map(sa -> sa.copy(host, lki)).collect(Collectors.toList()),
                this.getTriggers().stream().map(tr -> tr.copy(host, lki)).collect(Collectors.toList()),
                this.getReplacements().stream().map(re -> re.copy(host, lki)).collect(Collectors.toList()),
                this.getStaticAbilities().stream().map(st -> st.copy(host, lki)).collect(Collectors.toList()),
                remove
            );
    }

    public void changeText() {
        for (SpellAbility sa : this.getAbilities()) {
            sa.changeText();
        }

        for (Trigger tr : this.getTriggers()) {
            tr.changeText();
        }

        for (ReplacementEffect re : this.getReplacements()) {
            re.changeText();
        }

        for (StaticAbility sa : this.getStaticAbilities()) {
            sa.changeText();
        }
    }

    public List<SpellAbility> applySpellAbility(List<SpellAbility> list) {
        if (remove != null) {
            list.removeIf(remove);
        }
        list.removeAll(getRemovedAbilities());
        list.addAll(getAbilities());
        return list;
    }
    public List<Trigger> applyTrigger(List<Trigger> list) {
        if (remove != null) {
            list.removeIf(remove);
        }
        list.addAll(getTriggers());
        return list;
    }
    public List<ReplacementEffect> applyReplacementEffect(List<ReplacementEffect> list) {
        if (remove != null) {
            list.removeIf(remove);
        }
        list.addAll(getReplacements());
        return list;
    }
    public List<StaticAbility> applyStaticAbility(List<StaticAbility> list) {
        if (remove != null) {
            list.removeIf(remove);
        }
        list.addAll(getStaticAbilities());
        return list;
    }
}
