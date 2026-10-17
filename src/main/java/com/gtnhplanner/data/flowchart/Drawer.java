package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A drawer on the board: one resource crossing the plan's boundary, with what the player wants of
 * it. A {@link Kind#SOURCE} supplies node inputs (Oil, Water, a dust from the ME system); a
 * {@link Kind#PRODUCT} takes node outputs and carries the target (or, with no rule, takes whatever
 * arrives); {@link Kind#TRASH} voids what arrives. Older saves' byproducts load as products with no
 * rule.
 *
 * <p>
 * The {@link Rule} and {@link #getRate() rate} constrain the total flow through the drawer's
 * {@link Link links}: for a product, what the linked outputs deliver to it; for a source, what it
 * supplies to the linked inputs. Only sources and products have a rule the solver honours; trash
 * keeps the rule it had (so cycling back restores it) but the solver treats it as {@link Rule#ANY}
 * - see {@link #effectiveRule()}.
 *
 * <p>
 * Minecraft-free on purpose: the resource is remembered as an opaque {@link #getResourceKey() key}
 * the client writes and resolves (e.g. {@code "item:<registry name>:<meta>"} or
 * {@code "fluid:<fluid name>"}), plus a cached {@link #getLabel() label}, so a drawer survives with
 * no links and a save loads where the resource no longer exists.
 *
 * <p>
 * Edits made through the setters do not move the graph's version: call {@link Graph#touch()} after
 * changing a drawer that is on a graph, or use the graph's own drawer methods, which do.
 */
public class Drawer extends GraphData {

    public static final String TYPE = "drawer";

    /** What the drawer is for; decides which side of a node its links are on. */
    public enum Kind {

        /** Supplies node inputs. Links point at input ports. */
        SOURCE,
        /** What the player wants. Links take output ports. */
        PRODUCT,
        /**
         * Older saves' and plans' "byproduct": a product with no rule, which is what it was to the solver. Read only;
         * a drawer set to it becomes a product with no rule ({@link #setKind}, {@link #sanitize}).
         */
        BYPRODUCT,
        /** Voids what arrives. Links take output ports. */
        TRASH,
        /**
         * A drawer inside a resource network: its links are the output ports that feed it, its
         * {@link Drawer#getTakers()
         * takers} the input ports it feeds. Nothing it carries leaves the plan; its rule holds what passes through.
         */
        BUFFER;

        /** True when this kind's links are node INPUT ports (only {@link #SOURCE}). */
        public boolean linksInputs() {
            return this == SOURCE;
        }

        /** True when the solver honours this kind's rule (sources, products and buffers). */
        public boolean hasRule() {
            return this == SOURCE || this == PRODUCT || this == BUFFER;
        }

        /**
         * The board's cycle key: product, trash, then product again. A source has no cycle (its
         * links are inputs) and stays a source.
         */
        public Kind next() {
            return switch (this) {
                case SOURCE -> SOURCE;
                case BUFFER -> BUFFER;
                case PRODUCT, BYPRODUCT -> TRASH;
                case TRASH -> PRODUCT;
            };
        }
    }

    /** What the drawer's total flow must satisfy against {@link #getRate()}. */
    public enum Rule {
        /** No constraint; the drawer reads what the plan does. */
        ANY,
        /** total >= rate. */
        AT_LEAST,
        /** total == rate. */
        EXACTLY,
        /** total <= rate. */
        AT_MOST
    }

    /**
     * One node port the drawer is attached to. The direction is the drawer's: a source's links are
     * input ports, every other kind's are output ports. Immutable; equal by value.
     */
    public static final class Link {

        private final UUID nodeId;
        private final int portIndex;

        public Link(final UUID nodeId, final int portIndex) {
            this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
            this.portIndex = portIndex;
        }

        public UUID nodeId() {
            return nodeId;
        }

        public int portIndex() {
            return portIndex;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof final Link other)) return false;
            return portIndex == other.portIndex && nodeId.equals(other.nodeId);
        }

        @Override
        public int hashCode() {
            return 31 * nodeId.hashCode() + portIndex;
        }

        @Override
        public String toString() {
            return nodeId + "#" + portIndex;
        }
    }

    private Kind kind = Kind.PRODUCT;
    private Rule rule = Rule.ANY;
    /** Units per second (items/s, or L/s for fluids). Meaningless under {@link Rule#ANY}. */
    private double rate;
    private List<Link> links = new ArrayList<>();
    /** A buffer's users: the input ports it feeds. Empty for every other kind. */
    private List<Link> takers = new ArrayList<>();
    /** Opaque resource identity the client writes and resolves; never parsed by the engine. */
    private String resourceKey = "";
    /** The resource's display name as last resolved by the client, for when it cannot resolve. */
    private String label = "";
    /** The board group it is in ({@link BoardGroup}), or null. */
    @org.jetbrains.annotations.Nullable
    private UUID group;

    /** A product drawer with no resource, no links and no rule; also Gson's constructor. */
    public Drawer() {
        super(UUID.randomUUID());
    }

    public Drawer(final Kind kind, final String resourceKey) {
        this();
        this.kind = Objects.requireNonNull(kind, "kind");
        this.resourceKey = Objects.requireNonNull(resourceKey, "resourceKey");
        byproductToProduct();
    }

    /** Only for tests and loaders that need a fixed id. */
    public Drawer(final UUID id, final Kind kind, final String resourceKey) {
        super(id);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.resourceKey = Objects.requireNonNull(resourceKey, "resourceKey");
        byproductToProduct();
    }

    @Override
    public String getType() {
        return TYPE;
    }

    public Kind getKind() {
        return kind;
    }

    /**
     * Changes the kind. Switching between a source and an output kind would flip what every link
     * means (inputs versus outputs), so that drops the links; cycling among product, byproduct and
     * trash keeps them.
     */
    public void setKind(final Kind kind) {
        Objects.requireNonNull(kind, "kind");
        if (kind.linksInputs() != this.kind.linksInputs()) links.clear();
        if (kind != Kind.BUFFER) takers.clear();
        this.kind = kind;
        byproductToProduct();
    }

    /** A buffer's users, read-only: the input ports it feeds. */
    public List<Link> getTakers() {
        return Collections.unmodifiableList(takers);
    }

    /**
     * Links a port on the side it is: an output feeds the drawer, an input draws from it. A product given an input, or
     * a
     * source given an output, becomes a buffer (a source's links, which are inputs, become its takers); trash takes no
     * input. True when it was added.
     */
    public boolean addPort(final Link link, final boolean input) {
        Objects.requireNonNull(link, "link");
        if (kind == Kind.BUFFER) {
            final List<Link> side = input ? takers : links;
            if (side.contains(link)) return false;
            side.add(link);
            return true;
        }
        if (kind.linksInputs() == input) return addLink(link);
        if (kind == Kind.TRASH) return false;
        if (kind == Kind.SOURCE) {
            takers = new ArrayList<>(links);
            links = new ArrayList<>();
        }
        kind = Kind.BUFFER;
        (input ? takers : links).add(link);
        return true;
    }

    /**
     * Unlinks a port on its side. A buffer left with no users is a product again, with no makers a source again (its
     * takers its links); the rule stays. True when it was linked.
     */
    public boolean removePort(final Link link, final boolean input) {
        if (kind != Kind.BUFFER) return kind.linksInputs() == input && links.remove(link);
        final boolean removed = (input ? takers : links).remove(link);
        if (removed) settleBuffer();
        return removed;
    }

    /** A buffer missing a side becomes the end it still is: no users, a product; no makers, a source. */
    private void settleBuffer() {
        if (kind != Kind.BUFFER) return;
        if (takers.isEmpty()) kind = Kind.PRODUCT;
        else if (links.isEmpty()) {
            kind = Kind.SOURCE;
            links = takers;
            takers = new ArrayList<>();
        }
    }

    /** A byproduct (an older save's, a plan's) is a product with no rule. */
    private void byproductToProduct() {
        if (kind != Kind.BYPRODUCT) return;
        kind = Kind.PRODUCT;
        rule = Rule.ANY;
    }

    public Rule getRule() {
        return rule;
    }

    public void setRule(final Rule rule) {
        this.rule = Objects.requireNonNull(rule, "rule");
    }

    public double getRate() {
        return rate;
    }

    /** Units per second; negative or non-finite rates are stored as 0. */
    public void setRate(final double rate) {
        this.rate = Double.isFinite(rate) && rate > 0 ? rate : 0;
    }

    /** Sets the rule and the rate together, the way the drawer's rate box does. */
    public void setTarget(final Rule rule, final double rate) {
        setRule(rule);
        setRate(rate);
    }

    /** The links, read-only; change them through {@link #addLink}/{@link #removeLink} or the graph. */
    public List<Link> getLinks() {
        return Collections.unmodifiableList(links);
    }

    /** Adds a link unless it is already there; true when it was added. */
    public boolean addLink(final Link link) {
        Objects.requireNonNull(link, "link");
        if (links.contains(link)) return false;
        links.add(link);
        return true;
    }

    public boolean removeLink(final Link link) {
        return links.remove(link);
    }

    /** Drops every link (and every taker) to {@code nodeId}; true when any went. */
    public boolean removeLinksTo(final UUID nodeId) {
        final boolean any = links.removeIf(
            l -> l.nodeId()
                .equals(nodeId))
            | takers.removeIf(
                l -> l.nodeId()
                    .equals(nodeId));
        if (any) settleBuffer();
        return any;
    }

    public boolean isLinked(final UUID nodeId, final int portIndex) {
        return links.contains(new Link(nodeId, portIndex));
    }

    public String getResourceKey() {
        return resourceKey;
    }

    public void setResourceKey(final String resourceKey) {
        this.resourceKey = Objects.requireNonNull(resourceKey, "resourceKey");
    }

    @org.jetbrains.annotations.Nullable
    public UUID getGroup() {
        return group;
    }

    public void setGroup(@org.jetbrains.annotations.Nullable final UUID group) {
        this.group = group;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(final String label) {
        this.label = Objects.requireNonNull(label, "label");
    }

    /** The rule the solver applies: the stored rule for sources and products, ANY otherwise. */
    public Rule effectiveRule() {
        return kind.hasRule() ? rule : Rule.ANY;
    }

    /**
     * Whether this drawer sets the plan's scale on its own: an at-least or exactly rule with a
     * positive rate. An at-most rule only bounds, and a zero rate is satisfied by doing nothing.
     */
    public boolean isAnchor() {
        final Rule r = effectiveRule();
        return (r == Rule.AT_LEAST || r == Rule.EXACTLY) && rate > 0;
    }

    /**
     * Repairs what a hand-edited or older save can leave behind: unknown enum names (read as null),
     * a missing link list, links without a node, a non-finite rate.
     */
    void sanitize() {
        if (kind == null) kind = Kind.PRODUCT;
        if (rule == null) rule = Rule.ANY;
        byproductToProduct();
        if (!Double.isFinite(rate) || rate < 0) rate = 0;
        if (resourceKey == null) resourceKey = "";
        if (label == null) label = "";
        final List<Link> clean = new ArrayList<>();
        if (links != null) {
            for (final Link link : links) {
                if (link != null && link.nodeId != null && !clean.contains(link)) clean.add(link);
            }
        }
        links = clean;
        final List<Link> cleanTakers = new ArrayList<>();
        if (takers != null && kind == Kind.BUFFER) {
            for (final Link link : takers) {
                if (link != null && link.nodeId != null && !cleanTakers.contains(link)) cleanTakers.add(link);
            }
        }
        takers = cleanTakers;
        if (kind == Kind.BUFFER && links.isEmpty() && takers.isEmpty()) kind = Kind.PRODUCT;
        else settleBuffer();
    }

    /** Whether this drawer holds the port, given its own direction. */
    public boolean holds(final UUID nodeId, final int portIndex, final boolean input) {
        if (kind == Kind.BUFFER && input) return takers.contains(new Link(nodeId, portIndex));
        return kind.linksInputs() == input && isLinked(nodeId, portIndex);
    }

    /** One port a drawer holds, and on which side of its card: an input it supplies, or an output it takes. */
    public record Held(Link link, boolean input) {}

    /**
     * Every port the drawer holds with its side: its links on its kind's side, then a buffer's takers (inputs). Read
     * this, not {@link #getLinks()}, wherever both sides of a buffer count.
     */
    public List<Held> held() {
        final boolean input = kind.linksInputs();
        final List<Held> out = new ArrayList<>(links.size() + takers.size());
        for (final Link l : links) out.add(new Held(l, input));
        for (final Link l : takers) out.add(new Held(l, true));
        return out;
    }

    /** Whether a link is one of a buffer's users (an input it feeds) rather than on its kind's side. */
    public boolean isTaker(final Link link) {
        return takers.contains(link);
    }

    /** The side of the card a held link is on: true for an input (a source's links, a buffer's takers). */
    public boolean sideOf(final Link link) {
        return kind.linksInputs() || isTaker(link);
    }

    /**
     * Whether a port could be linked here on its side: its own side always; the other side by a product or a source,
     * which it makes a buffer ({@link #addPort}); never an input on trash.
     */
    public boolean takesSide(final boolean input) {
        if (kind == Kind.BUFFER || kind.linksInputs() == input) return true;
        return kind != Kind.TRASH;
    }

    /** Whether this is a buffer: a drawer inside the plan, not one of its ends. */
    public boolean isBuffer() {
        return kind == Kind.BUFFER;
    }
}
