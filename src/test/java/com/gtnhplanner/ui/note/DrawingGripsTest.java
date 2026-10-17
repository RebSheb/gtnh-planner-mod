package com.gtnhplanner.ui.note;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.ui.note.DrawingGrips.Grip;
import com.gtnhplanner.ui.note.DrawingGrips.Rect;

/** Where a box or arrow is held and what dragging each hold does (docs/design/annotations.md). */
class DrawingGripsTest {

    private static Note of(final String kind) {
        final Note n = new Note();
        n.setKind(kind);
        return n;
    }

    @Test
    void aBoxMovesByItsBorderAndResizesByCornersAndOuterEdges() {
        final Note box = of(Note.BOX);
        // 200 by 140: the border band inside, an edge just outside, the corners at the corners
        assertEquals(Grip.BODY, DrawingGrips.at(box, 200, 140, 100, 3));
        assertEquals(Grip.T, DrawingGrips.at(box, 200, 140, 100, -4));
        assertEquals(Grip.B, DrawingGrips.at(box, 200, 140, 100, 144));
        assertEquals(Grip.L, DrawingGrips.at(box, 200, 140, -4, 70));
        assertEquals(Grip.R, DrawingGrips.at(box, 200, 140, 204, 70));
        assertEquals(Grip.TL, DrawingGrips.at(box, 200, 140, 2, 2));
        assertEquals(Grip.BR, DrawingGrips.at(box, 200, 140, 203, 143));
        assertEquals(Grip.TR, DrawingGrips.at(box, 200, 140, 197, -3));
        assertEquals(Grip.BL, DrawingGrips.at(box, 200, 140, -3, 137));
        assertNull(DrawingGrips.at(box, 200, 140, 100, 70), "the inside lets clicks through");
        assertNull(DrawingGrips.at(box, 200, 140, -20, 70), "and so does the far outside");
    }

    @Test
    void aBoxResizesFromAnySideOnTheGridAndNeverBelowItsMinimum() {
        final Rect from = new Rect(100, 100, 200, 140);
        assertEquals(new Rect(100, 100, 260, 140), DrawingGrips.resized(from, Grip.R, 61, 99));
        assertEquals(new Rect(80, 100, 220, 140), DrawingGrips.resized(from, Grip.L, -20, 0));
        assertEquals(new Rect(100, 80, 200, 160), DrawingGrips.resized(from, Grip.T, 0, -20));
        assertEquals(new Rect(100, 100, 200, 160), DrawingGrips.resized(from, Grip.B, 0, 20));
        assertEquals(new Rect(90, 90, 210, 150), DrawingGrips.resized(from, Grip.TL, -10, -10));
        assertEquals(new Rect(100, 100, 230, 160), DrawingGrips.resized(from, Grip.BR, 30, 20));
        // dragged past the other side: stopped at the smallest, the far side not moving
        assertEquals(new Rect(280, 100, DrawingGrips.MIN, 140), DrawingGrips.resized(from, Grip.L, 500, 0));
        assertEquals(new Rect(100, 100, 200, DrawingGrips.MIN), DrawingGrips.resized(from, Grip.B, 0, -500));
    }

    @Test
    void anArrowIsHeldAtItsEndsAndByItsLine() {
        final Note arrow = of(Note.ARROW);
        arrow.setArrowDirection("down-right");
        assertEquals(Grip.TAIL, DrawingGrips.at(arrow, 160, 80, 2, 3));
        assertEquals(Grip.HEAD, DrawingGrips.at(arrow, 160, 80, 158, 82));
        assertEquals(Grip.BODY, DrawingGrips.at(arrow, 160, 80, 80, 40));
        assertNull(DrawingGrips.at(arrow, 160, 80, 80, 10));
    }

    @Test
    void anArrowEndMovesAndTheOtherStaysPut() {
        final Note arrow = of(Note.ARROW);
        arrow.setArrowDirection("down-right");
        final Rect from = new Rect(100, 100, 160, 0);
        // the head dragged down: tail (100,100) stays, head (260,100) goes to (260,180)
        final DrawingGrips.Shape down = DrawingGrips.reshaped(arrow, from, Grip.HEAD, 0, 80);
        assertEquals(new Rect(100, 100, 160, 80), down.box());
        assertEquals("down-right", down.direction());
        // the head dragged back past the tail to the left and up: the arrow flips corners
        final DrawingGrips.Shape flipped = DrawingGrips.reshaped(arrow, from, Grip.HEAD, -200, -40);
        assertEquals(new Rect(60, 60, 40, 40), flipped.box());
        assertEquals("up-left", flipped.direction());
        // the tail dragged: the head (260,100) stays
        final DrawingGrips.Shape tail = DrawingGrips.reshaped(arrow, from, Grip.TAIL, 60, 30);
        assertEquals(new Rect(160, 100, 100, 30), tail.box());
        assertEquals("up-right", tail.direction(), "the head is now above and right of the tail");
    }

    @Test
    void anArrowCanRunLevelButItsEndsNeverMeet() {
        final Note arrow = of(Note.ARROW);
        arrow.setArrowDirection("down-right");
        final Rect from = new Rect(100, 100, 160, 80);
        final DrawingGrips.Shape level = DrawingGrips.reshaped(arrow, from, Grip.HEAD, 0, -80);
        assertEquals(new Rect(100, 100, 160, 0), level.box());
        final DrawingGrips.Shape same = DrawingGrips.reshaped(arrow, from, Grip.HEAD, -160, -80);
        assertEquals(from, same.box(), "ends on one point: left as it was");
        assertEquals("down-right", same.direction());
    }
}
