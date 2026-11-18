package net.sourceforge.opencamera;

/** Zerno: adds fine film-like grain to a photo's brightness. A very clean picture (for example
 *  after merging many frames in the Night mode) can look smooth and "oily"; a little grain
 *  brings back the feeling of texture.
 *
 *  The grain is strongest in the mid-tones and fades towards pure black and white, as on film.
 *  It is slightly clumped (each grain is a little wider than one pixel). The noise comes from a
 *  hash of the pixel position, so it doesn't depend on how the photo is split into bands, and can
 *  be computed for the bands in parallel. This class has no Android dependencies.
 */
final class ZernoGrain {
    private final float amplitude; // in brightness steps (0..255), at the strongest

    /** @param level 1 (light) to 3 (strong). */
    ZernoGrain(int level) {
        this.amplitude = level == 1 ? 5.0f : level == 2 ? 9.0f : 14.0f;
    }

    /** A pseudo random number from -1 to 1 for a position. */
    private static float noise(int x, int y) {
        int h = x*0x1B873593 ^ y*0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return ((h & 0xffff) - 32768) / 32768.0f;
    }

    /** Adds grain to the rows y0..y0+rows-1 (ARGB pixels, row after row), in place. */
    void apply(int [] pixels, int y0, int rows, int width) {
        for(int row=0;row<rows;row++) {
            final int y = y0 + row;
            final int o = row*width;
            for(int x=0;x<width;x++) {
                final int c = pixels[o + x];
                final int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                final float luma = (77*r + 150*g + 29*b) / 65280.0f; // 0..1
                // strongest in the mid-tones, never quite zero
                final float weight = 0.25f + 0.75f * 4.0f * luma * (1.0f - luma);
                // two uniform numbers added make a bell shaped distribution; the neighbours are mixed in
                // so that a grain is a bit wider than a pixel
                final float n = 0.5f*(noise(x, y) + noise(x + 7919, y + 104729))
                        + 0.25f*(noise(x - 1, y) + noise(x, y - 1));
                final int change = Math.round(amplitude * weight * n);
                if( change == 0 )
                    continue;
                int nr = r + change, ng = g + change, nb = b + change;
                if( nr < 0 ) nr = 0; else if( nr > 255 ) nr = 255;
                if( ng < 0 ) ng = 0; else if( ng > 255 ) ng = 255;
                if( nb < 0 ) nb = 0; else if( nb > 255 ) nb = 255;
                pixels[o + x] = (c & 0xff000000) | (nr << 16) | (ng << 8) | nb;
            }
        }
    }
}
