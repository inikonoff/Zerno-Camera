package net.sourceforge.opencamera;

/** Zerno: sharpens (or softens) a photo by its brightness only - an "unsharp mask" - so edges
 *  get crisper without colours shifting.
 *
 *  Brightness is compared with a blurred copy of itself (a box blur); the difference is
 *  multiplied and added back to all three colour channels. Positive amounts sharpen, with a small
 *  dead zone so that the finest noise isn't boosted; negative amounts blend towards the blur.
 *
 *  The photo is processed in horizontal bands. A band needs the rows just above and below it
 *  (the "halo") as they were originally, so those are passed in separately; that way bands can
 *  be processed in parallel. This class has no Android dependencies.
 */
final class ZernoSharpen {
    private final int width;
    private final int radius;
    private final float amount;
    private final int dead_zone;

    /** @param level -5 (soft) to +5 (sharp), not 0. */
    ZernoSharpen(int width, int height, int level) {
        this.width = width;
        this.radius = Math.max(1, Math.round(Math.max(width, height) / 2500.0f));
        if( level > 0 ) {
            this.amount = level * 0.3f;
            this.dead_zone = 3;
        }
        else {
            this.amount = level * 0.15f;
            this.dead_zone = 0;
        }
    }

    /** The number of rows above and below a band that are needed to process it. */
    int halo() {
        return radius;
    }

    /** Sharpens the rows of a band, in place.
     * @param pixels The band's ARGB pixels, row after row.
     * @param rows The number of rows in the band.
     * @param above The (original) rows just above the band, or null if the band is at the top.
     * @param n_above The number of rows in above.
     * @param below The (original) rows just below the band, or null if the band is at the bottom.
     * @param n_below The number of rows in below.
     */
    void apply(int [] pixels, int rows, int [] above, int n_above, int [] below, int n_below) {
        final int total = n_above + rows + n_below;
        final int [] luma = new int[total*width];
        fillLuma(luma, 0, above, n_above);
        fillLuma(luma, n_above, pixels, rows);
        fillLuma(luma, n_above + rows, below, n_below);

        // horizontal window sums, row by row
        final int [] sums = new int[total*width];
        final int [] prefix = new int[width + 1];
        for(int row=0;row<total;row++) {
            final int o = row*width;
            for(int x=0;x<width;x++)
                prefix[x+1] = prefix[x] + luma[o + x];
            for(int x=0;x<width;x++) {
                int lo = Math.max(0, x - radius), hi = Math.min(width - 1, x + radius);
                sums[o + x] = prefix[hi + 1] - prefix[lo];
            }
        }

        for(int row=0;row<rows;row++) {
            final int t = n_above + row;
            final int top = Math.max(0, t - radius), bottom = Math.min(total - 1, t + radius);
            final int n_rows = bottom - top + 1;
            final int o = t*width;
            for(int x=0;x<width;x++) {
                int lo = Math.max(0, x - radius), hi = Math.min(width - 1, x + radius);
                int sum = 0;
                for(int r=top;r<=bottom;r++)
                    sum += sums[r*width + x];
                int blur = (sum + (hi - lo + 1)*n_rows/2) / ((hi - lo + 1)*n_rows);
                int delta = luma[o + x] - blur;
                if( amount > 0.0f ) {
                    if( delta > dead_zone )
                        delta -= dead_zone;
                    else if( delta < -dead_zone )
                        delta += dead_zone;
                    else
                        delta = 0;
                }
                int change = Math.round(amount * delta);
                if( change == 0 )
                    continue;
                int c = pixels[row*width + x];
                int r = ((c >> 16) & 0xff) + change;
                int g = ((c >> 8) & 0xff) + change;
                int b = (c & 0xff) + change;
                if( r < 0 ) r = 0; else if( r > 255 ) r = 255;
                if( g < 0 ) g = 0; else if( g > 255 ) g = 255;
                if( b < 0 ) b = 0; else if( b > 255 ) b = 255;
                pixels[row*width + x] = (c & 0xff000000) | (r << 16) | (g << 8) | b;
            }
        }
    }

    private void fillLuma(int [] luma, int first_row, int [] pixels, int rows) {
        if( pixels == null )
            return;
        for(int i=0;i<rows*width;i++) {
            int c = pixels[i];
            luma[first_row*width + i] = (77*((c >> 16) & 0xff) + 150*((c >> 8) & 0xff) + 29*(c & 0xff)) >> 8;
        }
    }
}
