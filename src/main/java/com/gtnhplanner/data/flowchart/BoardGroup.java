package com.gtnhplanner.data.flowchart;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import lombok.Getter;
import lombok.Setter;

/**
 * A group on the board: a named, coloured frame round part of the plan, moved as one and collapsed down to a summary
 * card ({@code docs/design/groups.md}). It is the website's board ({@code FactoryPocket}, plan JSON {@code pockets[]}),
 * so a plan's groups travel there and back: members say which group they are in ({@link Node#group},
 * {@link Drawer#getGroup()}, {@link Note#getGroup()}), groups nest through {@link #parent}. Groups take no part in the
 * solve.
 *
 * <p>
 * Not the old {@link Group} (the base of {@link MachineGroup}, a shared machine): that one is about the hardware.
 */
@Getter
@Setter
public class BoardGroup {

    /** The title strip's height, in board px. */
    public static final int STRIP = 20;
    /** The room kept round the members inside the frame, a grid cell... */
    public static final int PAD = 10;
    /** ...and the smallest a frame gets. */
    public static final int MIN_W = 160, MIN_H = 80;

    private final UUID id;
    /**
     * The website's id of the board this came from ({@code pocket-...}), kept so it goes back out the same; or null.
     */
    @Nullable
    private String ffId;
    private String name = "";
    /** A colour tag ({@code NoteColors}); null takes the theme's, or the one its id gives it. */
    @Nullable
    private String color;
    /** The group it sits in, or null for the board. */
    @Nullable
    private UUID parent;
    /** The frame, absolute board px; a collapsed group's summary card sits at its corner. */
    private int x, y, width = MIN_W, height = MIN_H;
    private boolean collapsed;
    /** The website's paper and ruling, kept for the round trip; never drawn. */
    @Nullable
    private String theme, pattern;

    public BoardGroup() {
        this(UUID.randomUUID());
    }

    public BoardGroup(final UUID id) {
        this.id = id;
    }

    /** The id the website knows it by. */
    public String webId() {
        return ffId != null && !ffId.isEmpty() ? ffId : "pocket-" + id;
    }

    /** The colour tag drawn: its own, else its paper's, else the paper its id gives it. */
    public String colorTag() {
        if (color != null && !color.isEmpty()) return color;
        return themeColor(theme != null && !theme.isEmpty() ? theme : paperForId(webId()));
    }

    /** The website's dark papers, in its order ({@code BOARD_PAPER_IDS}). */
    public static final List<String> PAPERS = List
        .of("slate", "blueprint", "chalkboard", "graphite", "gunmetal", "midnight", "charcoal", "void");

    /** The website's {@code paperForBoardId}: the same 31 hash Java's String has, into the dark papers. */
    public static String paperForId(final String id) {
        return PAPERS.get(Math.abs(id.hashCode()) % PAPERS.size());
    }

    /** A paper's colour tag (docs/design/groups.md, Colours); an unknown paper reads as steel. */
    public static String themeColor(final String theme) {
        return switch (theme) {
            case "blueprint" -> "azure";
            case "chalkboard" -> "green";
            case "graphite" -> "brown";
            case "gunmetal" -> "light_gray";
            case "midnight" -> "blue";
            case "charcoal" -> "onyx";
            case "void" -> "black";
            case "parchment" -> "amber";
            case "paper" -> "white";
            default -> "steel";
        };
    }

    /** The colours a new group takes, the first nobody wears. */
    public static final List<String> NEW_COLORS = List.of(
        "light_blue",
        "lime",
        "orange",
        "magenta",
        "yellow",
        "cyan",
        "pink",
        "purple",
        "scarlet",
        "emerald",
        "azure",
        "amber");

    /** The colour for a new group on a plan whose groups wear {@code worn}. */
    public static String newColor(final java.util.Collection<String> worn) {
        for (final String c : NEW_COLORS) if (!worn.contains(c)) return c;
        return NEW_COLORS.get(0);
    }

    /** A copy with a new id (copy and paste); the website id is not kept. */
    public BoardGroup copy() {
        final BoardGroup g = new BoardGroup();
        g.name = name;
        g.color = colorTag();
        g.parent = parent;
        g.x = x;
        g.y = y;
        g.width = width;
        g.height = height;
        g.collapsed = collapsed;
        g.theme = theme;
        g.pattern = pattern;
        return g;
    }
}
