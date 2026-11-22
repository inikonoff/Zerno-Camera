package net.sourceforge.opencamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Zerno: the spacing of the focus distances of a Macro series. */
public class ZernoFocusStepsTest {
    @Test
    public void endsAreExactlyAsGiven() {
        List<Float> d = ZernoFocusSteps.distances(7.3f, 4.1f, 9);
        assertEquals(9, d.size());
        assertEquals(7.3f, d.get(0), 0f);
        assertEquals(4.1f, d.get(8), 0f);
    }

    @Test
    public void stepsAreEqual() {
        for(int count : new int[]{2, 3, 5, 12, 40}) {
            List<Float> d = ZernoFocusSteps.distances(2.0f, 8.0f, count);
            assertEquals(count, d.size());
            final float step = 6.0f / (count - 1);
            for(int i=1;i<count;i++)
                assertEquals("count " + count + ", step " + i, step, d.get(i) - d.get(i-1), 1.0e-4f);
        }
    }

    @Test
    public void worksInBothDirections() {
        List<Float> up = ZernoFocusSteps.distances(2.0f, 8.0f, 7);
        List<Float> down = ZernoFocusSteps.distances(8.0f, 2.0f, 7);
        for(int i=0;i<7;i++)
            assertEquals(up.get(i), down.get(6 - i), 1.0e-4f);
        for(int i=1;i<7;i++)
            assertTrue(down.get(i) < down.get(i-1));
    }

    @Test
    public void degenerateCases() {
        assertTrue(ZernoFocusSteps.distances(1.0f, 5.0f, 0).isEmpty());
        List<Float> one = ZernoFocusSteps.distances(1.0f, 5.0f, 1);
        assertEquals(1, one.size());
        assertEquals(1.0f, one.get(0), 0f);
        // both ends the same: every frame at that distance
        for(float f : ZernoFocusSteps.distances(3.0f, 3.0f, 5))
            assertEquals(3.0f, f, 0f);
    }
}
