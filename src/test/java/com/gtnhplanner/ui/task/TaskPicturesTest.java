package com.gtnhplanner.ui.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Graph;

/** A task's screenshots: the thumbnail kept small, and a shot still waiting when the world goes is called off. */
class TaskPicturesTest {

    private static BufferedImage shot(final boolean noisy) {
        final int w = 1920, h = 1080;
        final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Random r = new Random(7);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            final int base = (x * 255 / w) << 16 | (y * 255 / h) << 8 | 90;
            img.setRGB(x, y, noisy ? r.nextInt(0xFFFFFF) : base ^ (r.nextInt(24) * 0x010101));
        }
        return img;
    }

    @Test
    void aThumbnailIsNarrowAndUnderTheByteLimit() throws Exception {
        for (final boolean noisy : new boolean[] { false, true }) {
            final BufferedImage thumb = TaskPictures.thumbnail(shot(noisy));
            assertEquals(240, thumb.getWidth());
            assertEquals(135, thumb.getHeight());
            final TaskPictures.Encoded e = TaskPictures.fit(thumb);
            assertTrue(e.bytes().length <= 12_000, "bytes " + e.bytes().length + " at " + e.quality());
            System.out.println("thumbnail noisy=" + noisy + " bytes=" + e.bytes().length + " q=" + e.quality());
        }
        assertTrue(
            TaskPictures.dataUrl(TaskPictures.thumbnail(shot(false)))
                .startsWith("data:image/jpeg;base64,"));
    }

    @Test
    void aShotWaitingWhenTheWorldGoesIsCalledOff() {
        final TaskPictures pictures = new TaskPictures();
        final AtomicBoolean hidden = new AtomicBoolean(true);
        pictures.begin(new Graph("Board"), "t", false, hidden::set);
        assertTrue(pictures.board != null);
        pictures.cancel();
        assertFalse(hidden.get(), "the overlays come back");
        assertNull(pictures.board);
        assertNull(pictures.taskId);
        assertFalse(pictures.reopen);
        // Called off again, or with nothing pending, does nothing more.
        hidden.set(true);
        pictures.cancel();
        assertTrue(hidden.get());
    }

    @Test
    void anOverlayHiddenBeforeTheShotStaysHidden() {
        final TaskPictures pictures = new TaskPictures();
        final AtomicBoolean hidden = new AtomicBoolean(true);
        pictures.begin(new Graph("Board"), "t", true, hidden::set);
        pictures.cancel();
        assertTrue(hidden.get());
        assertNull(pictures.board);
    }
}
