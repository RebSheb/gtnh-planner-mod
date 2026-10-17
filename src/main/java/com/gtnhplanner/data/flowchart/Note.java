package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import lombok.Getter;
import lombok.Setter;

/**
 * A sticky note on the board: text on a coloured square, moved and resized like a card, nothing to do with the solve.
 * It is Factory Flow's text annotation ({@code kind: "text"}), so a note survives a trip to the website and back:
 * {@link #color} is its colour tag and {@link #fontSize} its size in the website's pixels (14 reads as the board's
 * font at its own size).
 */
@Getter
@Setter
public class Note extends GraphData {

    /** The smallest a note gets, as on the website: five cells by two. */
    public static final int MIN_W = 100, MIN_H = 40;
    /** A new note's size. */
    public static final int DEFAULT_W = 180, DEFAULT_H = 120;
    /** The website's text sizes: 14 unless changed, in steps of 2 from 8 to 96. */
    public static final int DEFAULT_FONT = 14, MIN_FONT = 8, MAX_FONT = 96, FONT_STEP = 2;
    /** A new note's colour. */
    public static final String DEFAULT_COLOR = "yellow";

    /** The text, one entry per line. */
    @NotNull
    private List<String> text = new ArrayList<>();
    private int width = DEFAULT_W;
    private int height = DEFAULT_H;
    /** Factory Flow's colour tag: white, orange, magenta, light_blue, yellow, lime, pink, gray, ... */
    private String color = DEFAULT_COLOR;
    private int fontSize = DEFAULT_FONT;

    /** The website's annotation kinds that are drawings rather than writing; a note saved before them has none. */
    public static final String BOX = "box", ZONE = "zone", ARROW = "arrow", IMAGE = "image", TEXT = "text";
    /** The smallest a drawing gets: a cell. An arrow along an axis is a cell thick. */
    public static final int DRAWING_MIN = 10;

    /** {@code box}, {@code zone}, {@code arrow}, {@code image}; null or {@code text} for a sticky note. */
    @Nullable
    private String kind;
    /** Arrow: {@code down-right} (default), {@code down-left}, {@code up-right} or {@code up-left}, tail to head. */
    @Nullable
    private String arrowDirection;
    /** Zone: the outline's corners {x, y}, relative to the note, in the board's own px. */
    @Nullable
    private List<int[]> points;
    /** Arrow: {@code end} (default, a head at the end only) or {@code both}. */
    @Nullable
    private String arrowHeads;
    /** Arrow: the line's thickness in board px (1 thin, 2 normal and default, 4 thick); 0 is the default. */
    private int arrowWidth;
    /** Image: where the picture lives. The text is its caption. Pictures are kept in the data but never shown. */
    @Nullable
    private String imageUrl;
    /** Box, zone, image: {@code solid} (default), {@code dashed} or {@code none}. */
    @Nullable
    private String border;
    /** Colour tags for the border and the fill; null is the note's own colour. */
    @Nullable
    private String borderColor, fillColor;
    /** Box, zone: the surface, {@code tint} (default), {@code solid} or {@code none}. */
    @Nullable
    private String fill;
    /** Box, zone: {@code none} (default), {@code dots}, {@code grid}, {@code graph}, {@code ruled} or {@code hatch}. */
    @Nullable
    private String marks;
    /** The board group it is in ({@link BoardGroup}), or null. */
    @Nullable
    private UUID group;

    public Note() {
        super(UUID.randomUUID());
    }

    /** A note with a known id (one a shared plan knows it by). */
    public Note(final UUID id) {
        super(id);
    }

    @Override
    public String getType() {
        return "note";
    }

    /** The annotation kind, never null: {@code text} for a sticky note. */
    public String kind() {
        return kind == null || kind.isEmpty() ? TEXT : kind;
    }

    /** Whether this is a box, zone, arrow or image rather than a sticky note. */
    public boolean isDrawing() {
        return !TEXT.equals(kind());
    }

    /**
     * Pictures are no longer supported: a picture annotation is kept as it came (for a plan going back out) and not
     * shown.
     */
    public boolean isHidden() {
        return IMAGE.equals(kind());
    }

    /** An arrow's line thickness, 2 unless set. */
    public int arrowThickness() {
        return arrowWidth <= 0 ? 2 : Math.min(8, arrowWidth);
    }

    /** Whether an arrow wears a head at both ends. */
    public boolean bothHeads() {
        return "both".equals(arrowHeads);
    }

    /** Whether it sits behind the cards (a box or zone) rather than over the wires. */
    public boolean isBackdrop() {
        final String k = kind();
        return BOX.equals(k) || ZONE.equals(k);
    }

    /** The border style, never null. */
    public String borderStyle() {
        return border == null || border.isEmpty() ? "solid" : border;
    }

    /** The surface, never null. */
    public String fillStyle() {
        return fill == null || fill.isEmpty() ? "tint" : fill;
    }

    /** The marks, never null. */
    public String marksStyle() {
        return marks == null || marks.isEmpty() ? "none" : marks;
    }

    /** The text as one string, lines joined by newlines. */
    public String joined() {
        return String.join("\n", text);
    }

    /** Sets the text from one string; newlines split it into lines. */
    public void setJoined(final String joined) {
        text = new ArrayList<>(Arrays.asList((joined == null ? "" : joined).split("\n", -1)));
        if (text.size() == 1 && text.get(0)
            .isEmpty()) text.clear();
    }

    /** The colour tag, never null (notes saved before colours read as yellow). */
    public String colorTag() {
        return color == null || color.isEmpty() ? DEFAULT_COLOR : color;
    }

    /** The text size, clamped to the website's range (notes saved before sizes read as 14). */
    public int fontSizeOrDefault() {
        return fontSize <= 0 ? DEFAULT_FONT : Math.max(MIN_FONT, Math.min(MAX_FONT, fontSize));
    }

    /** A copy with a new id, for copy and paste. */
    public Note copy() {
        final Note n = new Note();
        n.x = x;
        n.y = y;
        n.header = header;
        n.text = new ArrayList<>(text);
        n.width = width;
        n.height = height;
        n.color = color;
        n.fontSize = fontSize;
        n.kind = kind;
        n.arrowDirection = arrowDirection;
        n.arrowHeads = arrowHeads;
        n.arrowWidth = arrowWidth;
        if (points != null) {
            n.points = new ArrayList<>();
            for (final int[] p : points) n.points.add(p.clone());
        }
        n.imageUrl = imageUrl;
        n.border = border;
        n.borderColor = borderColor;
        n.fillColor = fillColor;
        n.fill = fill;
        n.marks = marks;
        return n;
    }
}
