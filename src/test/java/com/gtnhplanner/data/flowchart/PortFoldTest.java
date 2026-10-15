package com.gtnhplanner.data.flowchart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.properties.ResourceProperty;

/**
 * How a recipe's ports fold when it is read (Node.deduplicate). Issue 487: Lazulia drops Lapis Dust and Lapis Lazuli,
 * which share an ore name, so they wire to each other's uses; read as outputs they folded into one and the Lapis
 * Lazuli was gone from the card. Outputs fold only when they are the same item; inputs still fold by ore name.
 */
class PortFoldTest {

    /** An item with an ore name: wires to anything with that name, is the same only as itself. */
    private static final class Stack {

        final String name, oreName;
        int amount;

        Stack(final String name, final String oreName, final int amount) {
            this.name = name;
            this.oreName = oreName;
            this.amount = amount;
        }
    }

    private static final ResourceProperty<Stack> ITEM = ResourceProperty.builder("test_item", new Stack("", "", 0))
        .amountExtractor(s -> s.amount)
        .amountUpdater((s, amount) -> s.amount = amount)
        .connectionChecker((a, b) -> a.name.equals(b.name) || a.oreName.equals(b.oreName))
        .sameChecker((a, b) -> a.name.equals(b.name))
        .displayFormatter(s -> s.name)
        .build();

    private static Port<Stack> port(final String name, final double exact) {
        final Port<Stack> p = new Port<>(ITEM, new Stack(name, "dyeBlue", 1), 1f);
        p.setExactAmount(exact);
        return p;
    }

    private static List<String> names(final List<Port<?>> ports) {
        final List<String> out = new ArrayList<>();
        for (final Port<?> p : ports) out.add(((Stack) p.getValue()).name);
        return out;
    }

    @Test
    void lazuliasTwoDropsStayTwoOutputs() {
        final List<Port<?>> outputs = new ArrayList<>(
            List.of(port("Lapis Dust", 1.5364682910645422), port("Lapis Lazuli", 0.768)));
        Node.deduplicate(outputs, true);
        assertEquals(List.of("Lapis Dust", "Lapis Lazuli"), names(outputs));
        assertEquals(
            1.5364682910645422,
            outputs.get(0)
                .amount(),
            1e-12);
        assertEquals(
            0.768,
            outputs.get(1)
                .amount(),
            1e-12);
    }

    @Test
    void inputsStillFoldByOreName() {
        final List<Port<?>> inputs = new ArrayList<>(List.of(port("Lapis Dust", 1), port("Lapis Lazuli", 1)));
        Node.deduplicate(inputs, false);
        assertEquals(List.of("Lapis Dust"), names(inputs));
        assertEquals(
            2,
            inputs.get(0)
                .amount(),
            1e-12);
    }

    @Test
    void oneItemListedTwiceIsOneOutputWithBothAmounts() {
        // A crop whose table lists an item twice: one output, carrying both drops (the second's was lost).
        final List<Port<?>> outputs = new ArrayList<>(
            List.of(port("Mana Bean", 0.25), port("Lapis Dust", 1), port("Mana Bean", 0.5)));
        Node.deduplicate(outputs, true);
        assertEquals(List.of("Mana Bean", "Lapis Dust"), names(outputs));
        assertEquals(
            0.75,
            outputs.get(0)
                .amount(),
            1e-12);
        assertEquals(
            2,
            outputs.get(0)
                .getAmount());
    }
}
