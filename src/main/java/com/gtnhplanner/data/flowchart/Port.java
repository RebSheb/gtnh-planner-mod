package com.gtnhplanner.data.flowchart;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.utils.Color;
import com.gtnhplanner.data.properties.ResourceProperty;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Port<T> {

    private final ResourceProperty<T> type;
    private final T value;
    private float chance;
    /**
     * The exact amount per craft when the type's whole number cannot hold it (a generator burns 0.37 L a second);
     * NaN when unset, and {@link #amount()} reads the type's own.
     */
    private double exactAmount = Double.NaN;
    /**
     * Added by a machine's model rather than read from its recipe (the Bacterial Vat's radio hatch material): the
     * website adds its own, so a posted plan leaves it out of the recipe.
     */
    public boolean fromModel;
    /**
     * The items an input's recipe slot takes, as NEI lists them (an ore dictionary slot's every member, GregTech's
     * alternatives), the recipe's own first; empty when it takes one thing only. A card can be set to any of them
     * ({@link Node#inputPicks}). Read from NEI on every refresh, never saved.
     */
    private List<T> alternatives = List.of();

    public Port(final ResourceProperty<T> type, final T value, final float chance) {
        this.type = type;
        this.value = value;
        this.chance = chance;
    }

    /** Whether the port can be set to another item (its slot lists more than one). */
    public boolean hasChoices() {
        return alternatives.size() > 1;
    }

    /** The port's resource key ({@code item:<registry name>:<meta>}, {@code fluid:<name>}). */
    public String key() {
        return type.key(value);
    }

    /** The key of one of this port's alternatives. */
    @SuppressWarnings("unchecked")
    public String keyOf(final Object alternative) {
        return type.key((T) alternative);
    }

    /** The recipe's own item for the slot: what the port is when nothing is picked. */
    public T own() {
        return alternatives.isEmpty() ? value : alternatives.getFirst();
    }

    /** Where the port's item is among its alternatives; -1 when it has none. */
    public int choiceIndex() {
        final String key = key();
        for (int i = 0; i < alternatives.size(); i++) if (type.key(alternatives.get(i))
            .equals(key)) return i;
        return -1;
    }

    /** The alternative with that key, or null. */
    @Nullable
    public T alternative(final String key) {
        for (final T alt : alternatives) if (type.key(alt)
            .equals(key)) return alt;
        return null;
    }

    /**
     * The same port holding another item (one of its alternatives): the same amount, chance and alternatives, the item
     * copied so no two ports share one.
     */
    public Port<T> withValue(final T other) {
        final T copy = type.copy(other);
        type.setAmount(copy, getAmount());
        final Port<T> p = new Port<>(type, copy, chance);
        p.exactAmount = exactAmount;
        p.fromModel = fromModel;
        p.alternatives = alternatives;
        return p;
    }

    /** {@link #withValue} for an alternative of a port whose type is not known here. */
    @SuppressWarnings("unchecked")
    public Port<?> withAlternative(final Object alternative) {
        return withValue((T) alternative);
    }

    public int getAmount() {
        return type.extractAmount(value);
    }

    /** Units per craft: the exact amount when one is set, else the type's whole number. */
    public double amount() {
        return Double.isNaN(exactAmount) ? getAmount() : exactAmount;
    }

    public String getDisplayName() {
        return type.formatDisplayName(value);
    }

    /** The ItemStack recipe viewers (NEI) show for this port's ingredient; null if none. */
    @Nullable
    public ItemStack getDisplayStack() {
        return type.displayStack(value);
    }

    /** Pin color for this port's ingredient; the type's pin color when none is derivable. */
    public int getPinColor(final boolean input) {
        return colorOr(input ? type.getPinInputColor() : type.getPinOutputColor());
    }

    /** Edge-arrow color for this port's ingredient; the type's arrow color as fallback. */
    public int getArrowColor() {
        return colorOr(type.getArrowColor());
    }

    /** Representative color for this port's ingredient; {@code fallback}'s alpha is kept. */
    private int colorOr(final int fallback) {
        final int rgb = type.color(value);
        if (rgb == -1) return fallback;
        return Color.withAlpha(rgb, Color.getAlpha(fallback));
    }

    @SuppressWarnings("unchecked")
    public boolean canConnect(final Port<?> other) {
        if (!type.equals(other.type)) return false;
        return ((ResourceProperty<Object>) type).canConnect(value, other.value);
    }

    /** Whether the other port carries the same resource (not just one it could wire to): two such outputs are one. */
    @SuppressWarnings("unchecked")
    public boolean same(final Port<?> other) {
        if (!type.equals(other.type)) return false;
        return ((ResourceProperty<Object>) type).same(value, other.value);
    }

    public void merge(final Port<?> other) {
        // Exact amounts add up too (a crop listing one item twice), else the second's would be lost.
        final double exact = Double.isNaN(exactAmount) && Double.isNaN(other.exactAmount) ? Double.NaN
            : amount() + other.amount();
        final int newAmount = getAmount() + other.getAmount();
        // An amount-weighted average is undefined when there is no amount to weight by, and the NaN
        // chance it would leave here reaches the balancer as a NaN coefficient, where it poisons a
        // whole solve instead of failing anywhere near this line.
        if (newAmount != 0) {
            this.chance = (this.getAmount() * this.chance + other.getAmount() * other.chance) / newAmount;
        }
        type.setAmount(value, newAmount);
        exactAmount = exact;
    }
}
