package com.gtnhplanner.ui.note;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Serializer;

/** Boxes, zones, arrows and pictures: where they take the pointer, their colours, and that they save. */
class DrawingPaintTest {

    private static Note of(final String kind) {
        final Note n = new Note();
        n.setKind(kind);
        n.setWidth(200);
        n.setHeight(100);
        return n;
    }

    @Test
    void aBoxTakesOnlyItsBorder() {
        final Note box = of(Note.BOX);
        assertTrue(DrawingPaint.hit(box, 200, 100, 100, 2), "on the top edge");
        assertTrue(DrawingPaint.hit(box, 200, 100, -5, 50), "just outside");
        assertFalse(DrawingPaint.hit(box, 200, 100, 100, 50), "the inside is click-through");
        assertFalse(DrawingPaint.hit(box, 200, 100, 100, -30), "far outside");
    }

    @Test
    void anArrowTakesOnlyItsLine() {
        final Note arrow = of(Note.ARROW);
        arrow.setArrowDirection("up-right");
        // Tail at the bottom left, head at the top right.
        assertTrue(DrawingPaint.hit(arrow, 200, 100, 100, 50), "the middle of the line");
        assertFalse(DrawingPaint.hit(arrow, 200, 100, 10, 10), "the empty corner");
        final float[] e = DrawingPaint.ends(arrow, 200, 100);
        assertEquals(0, e[0]);
        assertEquals(100, e[1]);
        assertEquals(200, e[2]);
        assertEquals(0, e[3]);
    }

    @Test
    void aZoneTakesItsOutlineAndKnowsItsInside() {
        final List<int[]> tri = List.of(new int[] { 0, 0 }, new int[] { 100, 0 }, new int[] { 0, 100 });
        assertTrue(DrawingPaint.inside(tri, 20, 20));
        assertFalse(DrawingPaint.inside(tri, 80, 80));
        final Note zone = of(Note.ZONE);
        zone.setPoints(tri);
        assertTrue(DrawingPaint.hit(zone, 100, 100, 50, 50), "on the long side");
        assertFalse(DrawingPaint.hit(zone, 100, 100, 20, 20), "inside is click-through");
    }

    @Test
    void coloursFollowTheTag() {
        final Note box = of(Note.BOX);
        box.setColor("azure");
        assertEquals(NoteColors.swatch("azure"), DrawingPaint.borderColor(box));
        box.setBorderColor("white");
        assertEquals(NoteColors.swatch("white"), DrawingPaint.borderColor(box));
        assertEquals(NoteColors.swatch("azure"), DrawingPaint.fillColor(box), "the fill keeps the note's colour");
        assertEquals(0x14, DrawingPaint.surface(box) >>> 24, "a tint");
        box.setFill("solid");
        assertEquals(0x47, DrawingPaint.surface(box) >>> 24);
        box.setFill("none");
        assertEquals(0, DrawingPaint.surface(box));
    }

    @Test
    void aPictureIsKeptButNeverShown() {
        final Note picture = of(Note.IMAGE);
        assertTrue(picture.isHidden());
        assertFalse(picture.isBackdrop());
        assertFalse(of(Note.BOX).isHidden());
        final Note back = Serializer.GSON.fromJson(Serializer.GSON.toJson(picture), Note.class);
        assertEquals(Note.IMAGE, back.kind(), "kept as it came");
    }

    @Test
    void anArrowIsTwoThickWithOneHeadUnlessItSaysOtherwise() {
        final Note arrow = of(Note.ARROW);
        assertEquals(2, arrow.arrowThickness());
        assertFalse(arrow.bothHeads());
        arrow.setArrowWidth(4);
        arrow.setArrowHeads("both");
        assertEquals(4, arrow.arrowThickness());
        assertTrue(arrow.bothHeads());
        final Note back = Serializer.GSON.fromJson(Serializer.GSON.toJson(arrow), Note.class);
        assertEquals(4, back.arrowThickness());
        assertTrue(back.bothHeads());
        assertEquals(
            4,
            arrow.copy()
                .arrowThickness());
        assertTrue(
            arrow.copy()
                .bothHeads());
        assertTrue(DrawingPaint.headLength(4) > DrawingPaint.headLength(2), "a thick line has a bigger head");
    }

    @Test
    void aLevelArrowIsStillTakenAlongItsLine() {
        final Note arrow = of(Note.ARROW);
        arrow.setArrowDirection("down-right");
        assertTrue(DrawingPaint.hit(arrow, 160, 0, 80, 4));
        assertFalse(DrawingPaint.hit(arrow, 160, 0, 80, 30));
    }

    @Test
    void drawingsSurviveASaveAndOlderNotesAreStillText() {
        final Note zone = of(Note.ZONE);
        zone.setPoints(List.of(new int[] { 0, 0 }, new int[] { 100, 0 }, new int[] { 50, 60 }));
        zone.setBorder("dashed");
        zone.setMarks("hatch");
        final Note back = Serializer.GSON.fromJson(Serializer.GSON.toJson(zone), Note.class);
        assertEquals(Note.ZONE, back.kind());
        assertEquals(
            3,
            back.getPoints()
                .size());
        assertEquals(
            50,
            back.getPoints()
                .get(2)[0]);
        assertEquals("dashed", back.borderStyle());
        assertEquals("hatch", back.marksStyle());
        final Note old = Serializer.GSON.fromJson("{\"width\":180,\"height\":120,\"color\":\"lime\"}", Note.class);
        assertFalse(old.isDrawing());
        assertEquals("text", old.kind());
        assertNull(old.getPoints());
    }
}
