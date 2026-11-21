package net.sourceforge.opencamera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/** Zerno: tests of the colour noise removal and the sharpening on synthetic images. These
 *  classes have no Android dependencies, so they run on the JVM.
 */
public class ZernoImageProcessingTest {
    private static final int OPAQUE = 0xff000000;

    private static int rgb(int r, int g, int b) {
        return OPAQUE | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    private static int luma(int c) {
        return (77*((c >> 16) & 0xff) + 150*((c >> 8) & 0xff) + 29*(c & 0xff)) >> 8;
    }

    private static int red(int c) {
        return (c >> 16) & 0xff;
    }

    private static int blue(int c) {
        return c & 0xff;
    }

    // ---- colour noise removal ----

    /** Runs the whole image through ZernoChromaDenoise, the way ZernoHooks does: all the bands
     *  are accumulated, then finish() is called, then the bands are applied (here in the given order).
     */
    private static int [] denoise(int [] image, int width, int height, int level, boolean reverse_apply_order) {
        ZernoChromaDenoise denoise = new ZernoChromaDenoise(width, height, level);
        final int band = denoise.bandHeight();
        final int n_bands = (height + band - 1) / band;
        final int [] result = image.clone();
        for(int b=0;b<n_bands;b++) {
            int y = b*band, rows = Math.min(band, height - y);
            int [] pixels = new int[width*rows];
            System.arraycopy(result, y*width, pixels, 0, width*rows);
            denoise.accumulate(pixels, y, rows);
        }
        denoise.finish();
        for(int i=0;i<n_bands;i++) {
            int b = reverse_apply_order ? n_bands - 1 - i : i;
            int y = b*band, rows = Math.min(band, height - y);
            int [] pixels = new int[width*rows];
            System.arraycopy(result, y*width, pixels, 0, width*rows);
            denoise.apply(pixels, y, rows);
            System.arraycopy(pixels, 0, result, y*width, width*rows);
        }
        return result;
    }

    /** A grey gradient with random colour noise (the same offset in R and B, the opposite in G). */
    private static int [] noisyGrey(int width, int height, int amplitude, long seed) {
        Random random = new Random(seed);
        int [] image = new int[width*height];
        for(int y=0;y<height;y++) {
            for(int x=0;x<width;x++) {
                int grey = 60 + (x*100)/width;
                int n = random.nextInt(2*amplitude + 1) - amplitude;
                int m = random.nextInt(2*amplitude + 1) - amplitude;
                image[y*width + x] = rgb(grey + n, grey - (n + m)/2, grey + m);
            }
        }
        return image;
    }

    /** Spread of the colour (not brightness) of the pixels: the mean absolute difference between R and B. */
    private static double colourSpread(int [] image) {
        double sum = 0;
        for(int c : image)
            sum += Math.abs(red(c) - blue(c));
        return sum / image.length;
    }

    @Test
    public void chromaDenoise_flatImageIsUnchanged() {
        final int width = 200, height = 150;
        int [] image = new int[width*height];
        java.util.Arrays.fill(image, rgb(120, 90, 60));
        for(int level=1;level<=ZernoChromaDenoise.MAX_LEVEL;level++) {
            assertArrayEquals("level " + level, image, denoise(image, width, height, level, false));
        }
    }

    @Test
    public void chromaDenoise_reducesColourNoise() {
        final int width = 240, height = 180;
        int [] image = noisyGrey(width, height, 12, 1);
        final double before = colourSpread(image);
        double previous = before;
        for(int level=1;level<=ZernoChromaDenoise.MAX_LEVEL;level++) {
            double after = colourSpread(denoise(image, width, height, level, false));
            assertTrue("level " + level + ": " + after + " should be less than " + before, after < before * 0.8);
            // a stronger level doesn't remove less than a weaker one
            assertTrue("level " + level + " weaker than the level before", after <= previous + 0.5);
            previous = after;
        }
    }

    @Test
    public void chromaDenoise_keepsBrightness() {
        final int width = 240, height = 180;
        int [] image = noisyGrey(width, height, 12, 2);
        for(int level=1;level<=ZernoChromaDenoise.MAX_LEVEL;level++) {
            int [] result = denoise(image, width, height, level, false);
            int worst = 0;
            for(int i=0;i<image.length;i++)
                worst = Math.max(worst, Math.abs(luma(image[i]) - luma(result[i])));
            // integer rounding of the YCbCr conversion; nothing like a visible change of brightness
            assertTrue("level " + level + ": brightness changed by " + worst, worst <= 3);
        }
    }

    @Test
    public void chromaDenoise_doesNotDependOnTheOrderOfBands() {
        // taller than one band, so that there are several
        final int width = 130, height = 700;
        int [] image = noisyGrey(width, height, 10, 3);
        for(int level=1;level<=ZernoChromaDenoise.MAX_LEVEL;level++) {
            assertArrayEquals("level " + level,
                    denoise(image, width, height, level, false), denoise(image, width, height, level, true));
        }
    }

    // ---- sharpening ----

    /** Runs the whole image through ZernoSharpen in bands, giving each band the original rows
     *  above and below it, as ZernoHooks does.
     */
    private static int [] sharpen(int [] image, int width, int height, int level, int band_height) {
        ZernoSharpen sharpen = new ZernoSharpen(width, height, level);
        final int halo = sharpen.halo();
        final int [] result = image.clone();
        for(int y=0;y<height;y+=band_height) {
            int rows = Math.min(band_height, height - y);
            int [] pixels = new int[width*rows];
            System.arraycopy(image, y*width, pixels, 0, width*rows);
            int [] above = null, below = null;
            if( y > 0 ) {
                above = new int[width*halo];
                System.arraycopy(image, (y - halo)*width, above, 0, width*halo);
            }
            if( y + rows < height ) {
                below = new int[width*halo];
                System.arraycopy(image, (y + rows)*width, below, 0, width*halo);
            }
            sharpen.apply(pixels, rows, above, above != null ? halo : 0, below, below != null ? halo : 0);
            System.arraycopy(pixels, 0, result, y*width, width*rows);
        }
        return result;
    }

    /** A vertical edge: grey 100 on the left, 160 on the right. */
    private static int [] edgeImage(int width, int height) {
        int [] image = new int[width*height];
        for(int y=0;y<height;y++)
            for(int x=0;x<width;x++)
                image[y*width + x] = x < width/2 ? rgb(100, 100, 100) : rgb(160, 160, 160);
        return image;
    }

    @Test
    public void sharpen_flatImageIsUnchanged() {
        final int width = 120, height = 90;
        int [] image = new int[width*height];
        java.util.Arrays.fill(image, rgb(120, 90, 60));
        for(int level : new int[]{-5, -1, 1, 5})
            assertArrayEquals("level " + level, image, sharpen(image, width, height, level, 32));
    }

    @Test
    public void sharpen_positiveLevelMakesEdgesCrisper() {
        final int width = 120, height = 60;
        int [] image = edgeImage(width, height);
        int [] result = sharpen(image, width, height, 3, 32);
        final int row = (height/2)*width;
        // just either side of the edge: darker on the dark side, lighter on the light side
        assertTrue("dark side should get darker", luma(result[row + width/2 - 1]) < 100);
        assertTrue("light side should get lighter", luma(result[row + width/2]) > 160);
        // far from the edge nothing changes
        assertEquals(100, luma(result[row + 5]));
        assertEquals(160, luma(result[row + width - 5]));
    }

    @Test
    public void sharpen_negativeLevelSoftensEdges() {
        final int width = 120, height = 60;
        int [] image = edgeImage(width, height);
        int [] result = sharpen(image, width, height, -4, 32);
        final int row = (height/2)*width;
        assertTrue("dark side should get lighter", luma(result[row + width/2 - 1]) > 100);
        assertTrue("light side should get darker", luma(result[row + width/2]) < 160);
    }

    @Test
    public void sharpen_doesNotBoostTheFinestNoise() {
        // brightness differences smaller than the dead zone must not be amplified
        final int width = 120, height = 90;
        Random random = new Random(5);
        int [] image = new int[width*height];
        for(int i=0;i<image.length;i++) {
            int v = 128 + random.nextInt(3) - 1; // +-1
            image[i] = rgb(v, v, v);
        }
        int [] result = sharpen(image, width, height, 5, 32);
        int worst = 0;
        for(int i=0;i<image.length;i++)
            worst = Math.max(worst, Math.abs(luma(image[i]) - luma(result[i])));
        assertEquals("noise of +-1 should be left alone", 0, worst);
    }

    @Test
    public void sharpen_bandsGiveTheSameResultAsOneBand() {
        final int width = 150, height = 200;
        Random random = new Random(7);
        int [] image = new int[width*height];
        for(int y=0;y<height;y++)
            for(int x=0;x<width;x++) {
                int v = ((x/10 + y/10) % 2 == 0 ? 70 : 170) + random.nextInt(9) - 4;
                image[y*width + x] = rgb(v, v + 5, v - 5);
            }
        for(int level : new int[]{-3, 2, 5}) {
            int [] whole = sharpen(image, width, height, level, height);
            assertArrayEquals("level " + level + ", bands of 40", whole, sharpen(image, width, height, level, 40));
            assertArrayEquals("level " + level + ", bands of 7", whole, sharpen(image, width, height, level, 7));
        }
    }

    @Test
    public void sharpen_leavesAlphaAndColourBalanceAlone() {
        // the same amount is added to R, G and B, so the differences between them stay as they were
        final int width = 120, height = 60;
        int [] image = new int[width*height];
        for(int y=0;y<height;y++)
            for(int x=0;x<width;x++)
                image[y*width + x] = x < width/2 ? rgb(110, 100, 90) : rgb(170, 160, 150);
        int [] result = sharpen(image, width, height, 4, 32);
        for(int i=0;i<image.length;i++) {
            int c = result[i];
            assertEquals(0xff, (c >>> 24));
            int g = (c >> 8) & 0xff;
            // away from clipping, R - G and G - B are exactly as before
            if( red(c) > 0 && red(c) < 255 && blue(c) > 0 && blue(c) < 255 ) {
                assertEquals(10, red(c) - g);
                assertEquals(10, g - blue(c));
            }
        }
    }
}
