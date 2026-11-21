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
 *  Usage: create, call accumulate() for each band (of bandHeight() rows), call finish(), then
 *  call apply() for each band. Bands may be processed in any order, and in parallel.
 */
final class ZernoChromaDenoise {
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

    /** The height of the bands to pass to accumulate() and apply(): a whole number of blocks,
     *  so that different bands never share a block (which lets bands be processed in parallel).
     */
    int bandHeight() {
        return block * 12;
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
        int [] temp_cb = new int[count.length];
        int [] temp_cr = new int[count.length];
        for(int pass=0;pass<2;pass++) {
            smooth(sum_cb, sum_cr, temp_cb, temp_cr);
            System.arraycopy(temp_cb, 0, sum_cb, 0, temp_cb.length);
            System.arraycopy(temp_cr, 0, sum_cr, 0, temp_cr.length);
        }
    }

    /** 3x3 average of the small colour planes (scaled by 16), into out_cb / out_cr. A neighbouring
     *  block is only included if its colour is within t_high of the block's own: otherwise the
     *  smoothing would mix the colours on the two sides of a real colour edge, and wash out the
     *  colour next to it.
     */
    private void smooth(int [] cb_plane, int [] cr_plane, int [] out_cb, int [] out_cr) {
        final int limit = t_high * 16;
        for(int y=0;y<small_h;y++) {
            for(int x=0;x<small_w;x++) {
                final int i = y*small_w + x;
                final int c_cb = cb_plane[i], c_cr = cr_plane[i];
                int sum_b = 0, sum_r = 0, n = 0;
                for(int yy=Math.max(y-1, 0);yy<=Math.min(y+1, small_h-1);yy++) {
                    for(int xx=Math.max(x-1, 0);xx<=Math.min(x+1, small_w-1);xx++) {
                        final int j = yy*small_w + xx;
                        if( Math.max(Math.abs(cb_plane[j] - c_cb), Math.abs(cr_plane[j] - c_cr)) < limit ) {
                            sum_b += cb_plane[j];
                            sum_r += cr_plane[j];
                            n++;
                        }
                    }
                }
                // n >= 1: the block itself is always within the limit of itself
                out_cb[i] = sum_b / n;
                out_cr[i] = sum_r / n;
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
                // bilinear sample of the smoothed colour (scaled by 16) - but only from those of the
                // four surrounding blocks whose colour is close to the pixel's own: at a real colour
                // edge, the blocks on the other side must not pull the colour towards them
                final int own_cb = cb(r, g, b) * 16, own_cr = cr(r, g, b) * 16;
                final int limit = t_high * 16;
                int total_weight = 0, acc_cb = 0, acc_cr = 0;
                for(int k=0;k<4;k++) {
                    final int j = ( (k & 2) != 0 ? row1 : row0 ) + ( (k & 1) != 0 ? x1 : x0 );
                    final int weight = ( (k & 1) != 0 ? wx : 256 - wx ) * ( (k & 2) != 0 ? y_weight : 256 - y_weight );
                    if( weight == 0 || Math.max(Math.abs(sum_cb[j] - own_cb), Math.abs(sum_cr[j] - own_cr)) >= limit )
                        continue;
                    total_weight += weight;
                    // the four weights add up to 65536 and the colours are within +-2048, so this fits in an int
                    acc_cb += weight * sum_cb[j];
                    acc_cr += weight * sum_cr[j];
                }
                if( total_weight == 0 )
                    continue; // no nearby block has a similar colour: a real colour edge, keep the pixel
                int smooth_cb = (acc_cb / total_weight) >> 4;
                int smooth_cr = (acc_cr / total_weight) >> 4;

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
