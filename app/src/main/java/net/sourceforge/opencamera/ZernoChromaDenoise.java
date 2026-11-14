package net.sourceforge.opencamera;

/** Zerno: removes colour (chroma) noise - the red/green/blue speckle in shadows - from a photo
 *  while leaving its brightness (luma) untouched, so fine detail and the natural grain survive.
 *
 *  The image is split into brightness and colour. The colour is averaged over small blocks and
 *  smoothed; each pixel then takes the smoothed colour, unless its own colour differs from it
 *  by more than noise would explain (a real colour edge), in which case it keeps its own.
 *  Brightness is never changed.
 *
 *  The image is processed in horizontal bands, so only a low resolution colour plane plus one
 *  band of pixels is held in memory. This class has no Android dependencies.
 *
 *  Usage: create, call accumulate() for each band from top to bottom, call finish(), then call
 *  apply() for each band from top to bottom.
 */
final class ZernoChromaDenoise {
    /** Band heights passed to accumulate()/apply() must be a multiple of this (except the last). */
    static final int BAND_MULTIPLE = 24;
    static final int MAX_LEVEL = 3;

    private final int width, height;
    private final int block; // colour is averaged over block x block pixels
    private final int t_low, t_high; // colour differences below t_low are noise, above t_high are real
    private final int small_w, small_h;
    private final int [] sum_cb, sum_cr; // per block: sums, then (after finish()) smoothed averages scaled by 16
    private final int [] count;
    // for each x: the left block to sample from, and the weight (0..256) of its right neighbour
    private final int [] x_index, x_weight;

    /** @param level 1 (light) to 3 (strong). */
    ZernoChromaDenoise(int width, int height, int level) {
        this.width = width;
        this.height = height;
        // scale the block size with the resolution, so the effect looks the same on a 12MP and a 50MP photo
        int scale = Math.max(1, Math.round(Math.max(width, height) / 4000.0f));
        switch( level ) {
            case 1: block = 4*scale; t_low = 14; t_high = 32; break;
            case 2: block = 6*scale; t_low = 24; t_high = 54; break;
            default: block = 8*scale; t_low = 40; t_high = 90; break;
        }
        small_w = (width + block - 1) / block;
        small_h = (height + block - 1) / block;
        sum_cb = new int[small_w*small_h];
        sum_cr = new int[small_w*small_h];
        count = new int[small_w*small_h];
        x_index = new int[width];
        x_weight = new int[width];
        for(int x=0;x<width;x++) {
            // position of this pixel's centre, in block units, relative to block centres
            int pos = ((2*x + 1) * 256) / (2*block) - 128; // fixed point, 256 = one block
            if( pos < 0 )
                pos = 0;
            int index = pos >> 8;
            int weight = pos & 255;
            if( index >= small_w - 1 ) {
                index = Math.max(small_w - 2, 0);
                weight = small_w > 1 ? 256 : 0;
            }
            x_index[x] = index;
            x_weight[x] = weight;
        }
    }

    private static int cb(int r, int g, int b) {
        return (-43*r - 85*g + 128*b) >> 8;
    }

    private static int cr(int r, int g, int b) {
        return (128*r - 107*g - 21*b) >> 8;
    }

    /** First pass: adds the rows y0..y0+rows-1 (ARGB pixels, row after row) to the colour averages. */
    void accumulate(int [] pixels, int y0, int rows) {
        for(int row=0;row<rows;row++) {
            int base = ((y0 + row) / block) * small_w;
            int offset = row*width;
            for(int x=0;x<width;x++) {
                int c = pixels[offset + x];
                int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                int i = base + x / block;
                sum_cb[i] += cb(r, g, b);
                sum_cr[i] += cr(r, g, b);
                count[i]++;
            }
        }
    }

    /** Between the passes: turns the sums into averages, and smooths them. */
    void finish() {
        for(int i=0;i<count.length;i++) {
            if( count[i] > 0 ) {
                sum_cb[i] = (sum_cb[i] * 16) / count[i];
                sum_cr[i] = (sum_cr[i] * 16) / count[i];
            }
        }
        int [] temp = new int[count.length];
        for(int pass=0;pass<2;pass++) {
            boxBlur(sum_cb, temp);
            boxBlur(sum_cr, temp);
        }
    }

    /** 3x3 box blur of a small_w x small_h plane, in place. */
    private void boxBlur(int [] plane, int [] temp) {
        // horizontal
        for(int y=0;y<small_h;y++) {
            int o = y*small_w;
            for(int x=0;x<small_w;x++) {
                int left = plane[o + Math.max(x-1, 0)], right = plane[o + Math.min(x+1, small_w-1)];
                temp[o + x] = (left + plane[o + x] + right) / 3;
            }
        }
        // vertical
        for(int y=0;y<small_h;y++) {
            int o = y*small_w, up = Math.max(y-1, 0)*small_w, down = Math.min(y+1, small_h-1)*small_w;
            for(int x=0;x<small_w;x++) {
                plane[o + x] = (temp[up + x] + temp[o + x] + temp[down + x]) / 3;
            }
        }
    }

    /** Second pass: replaces the colour of the rows y0..y0+rows-1, in place. */
    void apply(int [] pixels, int y0, int rows) {
        final int range = t_high - t_low;
        for(int row=0;row<rows;row++) {
            int y = y0 + row;
            int pos = ((2*y + 1) * 256) / (2*block) - 128;
            if( pos < 0 )
                pos = 0;
            int y_index = pos >> 8;
            int y_weight = pos & 255;
            if( y_index >= small_h - 1 ) {
                y_index = Math.max(small_h - 2, 0);
                y_weight = small_h > 1 ? 256 : 0;
            }
            int row0 = y_index*small_w;
            int row1 = Math.min(y_index + 1, small_h - 1)*small_w;
            int offset = row*width;
            for(int x=0;x<width;x++) {
                int c = pixels[offset + x];
                int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
                int x0 = x_index[x], x1 = Math.min(x0 + 1, small_w - 1), wx = x_weight[x];
                // bilinear sample of the smoothed colour (scaled by 16)
                int top = sum_cb[row0 + x0] * (256 - wx) + sum_cb[row0 + x1] * wx;
                int bottom = sum_cb[row1 + x0] * (256 - wx) + sum_cb[row1 + x1] * wx;
                int smooth_cb = ((top >> 8) * (256 - y_weight) + (bottom >> 8) * y_weight) >> 12;
                top = sum_cr[row0 + x0] * (256 - wx) + sum_cr[row0 + x1] * wx;
                bottom = sum_cr[row1 + x0] * (256 - wx) + sum_cr[row1 + x1] * wx;
                int smooth_cr = ((top >> 8) * (256 - y_weight) + (bottom >> 8) * y_weight) >> 12;

                // how far the pixel's colour should move towards the smoothed colour
                int d_cb = smooth_cb - cb(r, g, b);
                int d_cr = smooth_cr - cr(r, g, b);
                int diff = Math.max(Math.abs(d_cb), Math.abs(d_cr));
                if( diff == 0 || diff >= t_high )
                    continue; // nothing to do, or a real colour edge: keep the pixel
                if( diff > t_low ) {
                    // in between: move part of the way
                    int keep = ((t_high - diff) * 256) / range;
                    d_cb = (d_cb * keep) >> 8;
                    d_cr = (d_cr * keep) >> 8;
                }
                // changing only Cb/Cr leaves the brightness as it was
                r += (359 * d_cr) >> 8;
                g -= (88 * d_cb + 183 * d_cr) >> 8;
                b += (454 * d_cb) >> 8;
                if( r < 0 ) r = 0; else if( r > 255 ) r = 255;
                if( g < 0 ) g = 0; else if( g > 255 ) g = 255;
                if( b < 0 ) b = 0; else if( b > 255 ) b = 255;
                pixels[offset + x] = (c & 0xff000000) | (r << 16) | (g << 8) | b;
            }
        }
    }
}
