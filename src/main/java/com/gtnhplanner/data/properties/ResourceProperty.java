package com.gtnhplanner.data.properties;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import lombok.Getter;
import lombok.experimental.SuperBuilder;

@SuperBuilder(builderMethodName = "emptyBuilder")
public class ResourceProperty<T> extends SummaryProperty<T> {

    @lombok.Builder.Default
    private final ToIntFunction<T> amountExtractor = (v) -> 1;
    @lombok.Builder.Default
    private final BiConsumer<T, Integer> amountUpdater = (v, amount) -> {};

    @lombok.Builder.Default
    private final BiPredicate<T, T> connectionChecker = (a, b) -> true;
    /**
     * Whether two values are the same resource, so two outputs of them are one: stricter than connecting, which lets
     * an item go to anything sharing an ore name with it. Null: as {@link #canConnect}.
     */
    @lombok.Builder.Default
    @Nullable
    private final BiPredicate<T, T> sameChecker = null;
    @lombok.Builder.Default
    private final ToIntFunction<T> hashCodeExtractor = Objects::hashCode;

    /**
     * The value as a resource key (an item's {@code item:<registry name>:<meta>}, a fluid's {@code fluid:<name>}): what
     * a card's pick among an input's items is saved as.
     */
    @lombok.Builder.Default
    private final Function<T, String> keyExtractor = String::valueOf;
    /** A copy of a value a port can own (its amount is written into it). */
    @lombok.Builder.Default
    private final java.util.function.UnaryOperator<T> copier = v -> v;

    @lombok.Builder.Default
    private final Function<T, ItemStack> displayStackProvider = (v) -> null;
    @lombok.Builder.Default
    private final ToIntFunction<T> colorProvider = (v) -> -1;

    // Pin/arrow fallback colors; opaque white so a type that declares none stays visible.
    @Getter
    @lombok.Builder.Default
    private final int pinInputColor = 0xFFFFFFFF;
    @Getter
    @lombok.Builder.Default
    private final int pinOutputColor = 0xFFFFFFFF;
    @Getter
    @lombok.Builder.Default
    private final int arrowColor = 0xFFFFFFFF;

    @Override
    public String displayName() {
        return getKey();
    }

    public int extractAmount(final T value) {
        return amountExtractor.applyAsInt(value);
    }

    public void setAmount(final T value, final int newAmount) {
        amountUpdater.accept(value, newAmount);
    }

    public boolean canConnect(final T a, final T b) {
        return connectionChecker.test(a, b);
    }

    /** Whether two values are the same resource (see {@link #sameChecker}). */
    public boolean same(final T a, final T b) {
        return sameChecker != null ? sameChecker.test(a, b) : connectionChecker.test(a, b);
    }

    public int hashValue(final T value) {
        return hashCodeExtractor.applyAsInt(value);
    }

    /** The value's resource key; see {@link #keyExtractor}. */
    public String key(final T value) {
        return keyExtractor.apply(value);
    }

    public T copy(final T value) {
        return copier.apply(value);
    }

    /** The ItemStack recipe viewers (NEI) show for this value; null if it has none. */
    @Nullable
    public ItemStack displayStack(final T value) {
        return displayStackProvider.apply(value);
    }

    /** Representative bare-RGB color for this value, or -1 when none is derivable. */
    public int color(final T value) {
        return colorProvider.applyAsInt(value);
    }

    public static <B> ResourcePropertyBuilder<B, ?, ?> builder(final String key, final B defaultValue) {
        return ResourceProperty.<B>emptyBuilder()
            .key(key)
            .defaultValue(defaultValue);
    }
}
