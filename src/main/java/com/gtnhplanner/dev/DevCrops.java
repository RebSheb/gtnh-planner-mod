package com.gtnhplanner.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.provider.CropsNhProvider;
import com.gtnhplanner.machines.CropsNh;
import com.gtnhplanner.machines.game.CropCards;

/**
 * {@code call crops}: every crop on CropsNH's NEI page made into a card, its drop table against the outputs the card
 * gets. {@code lost} lists a crop whose card has fewer outputs than it has different items, {@code amounts} one whose
 * outputs do not add up to its drops, and {@code sharedOreNames} the drops that share an ore name: before issue 487 a
 * card folded those into one output and lost the second (Lazulia's Lapis Lazuli into its Lapis Dust). {@code items=}
 * (website ids, comma separated) lists those items' ore names instead, in any pack. The full pack only (CropsNH);
 * client thread.
 */
final class DevCrops {

    private DevCrops() {}

    static Map<String, Object> report() {
        final com.gtnewhorizon.cropsnh.compatibility.NEI.NEICropsNHCropHandler page = new com.gtnewhorizon.cropsnh.compatibility.NEI.NEICropsNHCropHandler();
        page.loadCraftingRecipes(page.getOverlayIdentifier());
        int crops = 0;
        final List<String> lost = new ArrayList<>(), amounts = new ArrayList<>(), shared = new ArrayList<>();
        for (int i = 0; i < page.arecipes.size(); i++) {
            final com.gtnewhorizon.cropsnh.api.ICropCard card = CropsNhProvider.card(page, i);
            if (card == null) continue;
            crops++;
            final List<ItemStack> drops = new ArrayList<>();
            for (final Map.Entry<ItemStack, Integer> e : card.getDropTable()
                .entrySet())
                if (e.getKey() != null && e.getKey()
                    .getItem() != null && e.getValue() != null && e.getValue() > 0) drops.add(e.getKey());
            final Node node = new Node(page, i, 0, 0);
            final List<ItemStack> items = new ArrayList<>();
            for (final ItemStack d : drops) {
                boolean seen = false;
                for (final ItemStack s : items) seen |= s.isItemEqual(d) && ItemStack.areItemStackTagsEqual(s, d);
                if (!seen) items.add(d);
            }
            if (node.outputs.size() < items.size())
                lost.add(card.getId() + ": " + items.size() + " items, " + node.outputs.size() + " outputs");
            final CropsNh.Stats stats = CropCards.stats(node);
            if (stats != null) {
                double want = 0, got = 0;
                for (final CropsNh.Drop d : stats.drops())
                    want += CropsNh.expectedDrop(stats, CropsNh.REFERENCE.gain(), d);
                for (final Port<?> p : node.outputs) got += p.amount();
                if (Math.abs(want - got) > 1e-9 * Math.max(1, want))
                    amounts.add(card.getId() + ": drops " + want + " a harvest, outputs " + got);
            }
            for (int a = 0; a < drops.size(); a++) for (int b = a + 1; b < drops.size(); b++) {
                final Set<String> both = names(drops.get(a));
                both.retainAll(names(drops.get(b)));
                if (!both.isEmpty() && !drops.get(a)
                    .isItemEqual(drops.get(b))) shared.add(
                        card.getId() + ": "
                            + drops.get(a)
                                .getDisplayName()
                            + " and "
                            + drops.get(b)
                                .getDisplayName()
                            + " share "
                            + String.join(", ", both));
            }
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("crops", crops);
        out.put("lost", lost);
        out.put("amounts", amounts);
        out.put("sharedOreNames", shared);
        return out;
    }

    /** The ore names of items by website id ("minecraft:dye@4"). */
    static Map<String, Object> items(final String ids) {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final String raw : ids.split(",")) {
            final String id = raw.trim();
            if (id.isEmpty()) continue;
            final int at = id.lastIndexOf('@');
            final String name = at < 0 ? id : id.substring(0, at);
            Object item = Item.itemRegistry.getObject(name);
            // Website ids are in lower case; the registry's names are not (IC2:blockRubWood).
            if (item == null) for (final Object key : Item.itemRegistry.getKeys())
                if (key instanceof final String k && k.equalsIgnoreCase(name)) item = Item.itemRegistry.getObject(k);
            if (!(item instanceof final Item it)) {
                out.put(id, "no such item");
                continue;
            }
            final ItemStack stack = new ItemStack(it, 1, at < 0 ? 0 : Integer.parseInt(id.substring(at + 1)));
            out.put(id, stack.getDisplayName() + ": " + String.join(", ", names(stack)));
        }
        return out;
    }

    private static Set<String> names(final ItemStack stack) {
        final Set<String> out = new LinkedHashSet<>();
        for (final int id : OreDictionary.getOreIDs(stack)) out.add(OreDictionary.getOreName(id));
        return out;
    }
}
