package net.sourceforge.opencamera;

import android.graphics.Bitmap;

/** Zerno: the single point of contact between the (otherwise unmodified) Open Camera engine and
 *  the Zerno UI layer (package ru.inikonoff.zerno). The engine calls the static methods below at
 *  a handful of places marked with "Zerno:" comments; everything else is done by the adapter
 *  through the engine's existing public API.
 *  Keeping all hooks here keeps the diff against upstream Open Camera small and easy to re-apply.
 */
public final class ZernoHooks {
    /** If true, the stock Open Camera on-screen UI (buttons, popup menu, info text) is hidden and
     *  the Zerno Compose UI is shown instead.
     */
    public static final boolean ZERNO_UI = true;

    /** Maximum zoom ratio (scaled by 100) offered to the user, for both pinch zoom and the zoom
     *  controls.
     */
    public static final int MAX_ZOOM_RATIO_PERCENT = 400;

    public interface Listener {
        /** Called from MainActivity.onCreate(), before the Preview (and hence the camera) is
         *  created. Preferences that affect how the camera is opened must be set here.
         */
        void onBeforePreviewCreated(MainActivity activity, boolean first_run);
        /** Called at the end of MainActivity.onCreate(). */
        void onActivityCreated(MainActivity activity);
        /** Called at the end of MainActivity.cameraSetup(), i.e. whenever a camera has been
         *  (re)opened and its features are known.
         */
        void onCameraSetup(MainActivity activity);
        /** Called whenever the thumbnail of the most recent photo changes (may be null). */
        void onGalleryThumbnail(MainActivity activity, Bitmap thumbnail);
        /** Called when the settings screen is opened or closed. */
        void onSettingsVisible(MainActivity activity, boolean visible);
        void onActivityDestroyed(MainActivity activity);
    }

    private static Listener listener;

    // ---- metering ----

    public static final int METERING_SPOT = 0; // on the focus area
    public static final int METERING_CENTRE = 1; // centre-weighted: the middle of the frame
    public static final int METERING_MATRIX = 2; // the whole frame
    public static volatile int metering_mode = METERING_SPOT;

    /** Returns the area (in the engine's -1000..1000 camera coordinates) that the exposure
     *  should be metered on, given the focus area.
     */
    public static net.sourceforge.opencamera.cameracontroller.CameraController.Area meteringArea(net.sourceforge.opencamera.cameracontroller.CameraController.Area focus_area) {
        if( !ZERNO_UI )
            return focus_area;
        switch( metering_mode ) {
            case METERING_CENTRE:
                return new net.sourceforge.opencamera.cameracontroller.CameraController.Area(new android.graphics.Rect(-500, -500, 500, 500), 1000);
            case METERING_MATRIX:
                return new net.sourceforge.opencamera.cameracontroller.CameraController.Area(new android.graphics.Rect(-1000, -1000, 1000, 1000), 1000);
            default:
                return focus_area;
        }
    }

    // ---- image adjustment ----

    /** A 4x5 colour matrix (as used by android.graphics.ColorMatrix) applied to every saved
     *  photo, or null for no adjustment. Set by the adapter from the brightness / contrast /
     *  saturation settings.
     */
    public static volatile float [] color_matrix;

    /** Strength of the colour noise removal applied to every saved photo: 0 (off) to
     *  ZernoChromaDenoise.MAX_LEVEL.
     */
    public static volatile int chroma_denoise;

    /** Applies the colour noise removal and color_matrix to the photo. If there is nothing to
     *  do, returns the supplied bitmap (which may be null, meaning that the JPEG data is saved
     *  untouched). Works in place, in bands, so that no second full-size bitmap is needed.
     */
    static Bitmap adjustImage(byte [] data, Bitmap bitmap) {
        final float [] matrix = color_matrix;
        final int chroma_level = chroma_denoise;
        if( !ZERNO_UI || ( matrix == null && chroma_level <= 0 ) )
            return bitmap;
        try {
            if( bitmap == null ) {
                bitmap = ImageUtils.loadBitmapWithRotation(data, true);
                if( bitmap == null )
                    return null; // couldn't decode: save the photo unadjusted
            }
            if( !bitmap.isMutable() ) {
                Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                if( copy == null )
                    return bitmap;
                bitmap.recycle();
                bitmap = copy;
            }
            final int width = bitmap.getWidth(), height = bitmap.getHeight();
            if( chroma_level > 0 ) {
                final ZernoChromaDenoise denoise = new ZernoChromaDenoise(width, height, chroma_level);
                runInBands(bitmap, denoise.bandHeight(), false, new BandTask() {
                    @Override
                    public void run(int [] pixels, int y, int rows) {
                        denoise.accumulate(pixels, y, rows);
                    }
                });
                denoise.finish();
                runInBands(bitmap, denoise.bandHeight(), true, new BandTask() {
                    @Override
                    public void run(int [] pixels, int y, int rows) {
                        denoise.apply(pixels, y, rows);
                    }
                });
            }
            if( matrix != null ) {
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(matrix));
                paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC));
                android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                final int band_height = 256;
                for(int y=0;y<height;y+=band_height) {
                    int h = Math.min(band_height, height - y);
                    Bitmap band = Bitmap.createBitmap(bitmap, 0, y, width, h);
                    canvas.drawBitmap(band, 0, y, paint);
                    if( band != bitmap )
                        band.recycle();
                }
            }
        }
        catch(RuntimeException | OutOfMemoryError e) {
            MyDebug.logStackTrace("ZernoHooks", "failed to adjust image", e);
        }
        return bitmap;
    }

    private interface BandTask {
        void run(int [] pixels, int y, int rows);
    }

    /** Runs a task over the bitmap in horizontal bands, on several threads (each band is only
     *  ever touched by one thread).
     * @param write_back Whether the task modifies the pixels, which must then be stored.
     */
    private static void runInBands(final Bitmap bitmap, final int band_height, final boolean write_back, final BandTask task) {
        final int width = bitmap.getWidth(), height = bitmap.getHeight();
        final int n_bands = (height + band_height - 1) / band_height;
        final int n_threads = Math.max(1, Math.min(4, Math.min(Runtime.getRuntime().availableProcessors(), n_bands)));
        final java.util.concurrent.atomic.AtomicInteger next_band = new java.util.concurrent.atomic.AtomicInteger(0);
        final Throwable [] failure = new Throwable[1];
        Thread [] threads = new Thread[n_threads];
        for(int i=0;i<n_threads;i++) {
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        int [] pixels = new int[width*band_height];
                        int band;
                        while( (band = next_band.getAndIncrement()) < n_bands ) {
                            int y = band*band_height;
                            int rows = Math.min(band_height, height - y);
                            bitmap.getPixels(pixels, 0, width, 0, y, width, rows);
                            task.run(pixels, y, rows);
                            if( write_back )
                                bitmap.setPixels(pixels, 0, width, 0, y, width, rows);
                        }
                    }
                    catch(Throwable t) {
                        failure[0] = t;
                    }
                }
            }, "ZernoImage" + i);
            threads[i].start();
        }
        for(Thread thread : threads) {
            try {
                thread.join();
            }
            catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if( failure[0] != null )
            throw new RuntimeException("image processing failed", failure[0]);
    }

    // ---- exif ----

    /** A one-line description of Zerno's settings for the photo being taken (plain ASCII), set
     *  by the adapter when the shutter is pressed; written to the photo's ImageDescription tag.
     */
    public static volatile String exif_description;
    /** Written to the photo's Software tag. */
    public static volatile String exif_software;

    static boolean hasCustomExif() {
        return ZERNO_UI && exif_description != null;
    }

    static void customExif(androidx.exifinterface.media.ExifInterface exif) {
        if( !ZERNO_UI )
            return;
        final String description = exif_description;
        if( description != null )
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_DESCRIPTION, description);
        final String software = exif_software;
        if( software != null )
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE, software);
    }

    // ---- timing ----

    public static final int MARK_CREATE = 0; // MainActivity.onCreate()
    public static final int MARK_RESUME = 1; // MainActivity.onResume()
    public static final int MARK_CAPTURE_STARTED = 2; // the camera has started exposing
    public static final int MARK_IMAGE_RECEIVED = 3; // the camera has delivered the image(s)
    public static final int MARK_CAPTURE_COMPLETED = 4; // the engine considers the photo taken
    public static final int MARK_FIRST_RESUME = 5; // the first onResume() after onCreate()
    public static final int MARK_CAPTURE_REQUESTED = 6; // focusing is done, the camera is asked for the photo
    private static final long [] marks = new long[7];

    /** Records when something happened (SystemClock.uptimeMillis()), for the diagnostics
     *  report. May be called from any thread.
     */
    public static void mark(int what) {
        final long now = android.os.SystemClock.uptimeMillis();
        marks[what] = now;
        if( what == MARK_CREATE )
            marks[MARK_FIRST_RESUME] = 0;
        else if( what == MARK_RESUME && marks[MARK_FIRST_RESUME] == 0 )
            marks[MARK_FIRST_RESUME] = now;
    }

    public static long getMark(int what) {
        return marks[what];
    }

    private static volatile long suppress_toasts_until; // SystemClock.uptimeMillis() time

    private ZernoHooks() {
    }

    /** Suppresses the engine's informational toasts for the given time. Called by the adapter
     *  just before it changes a setting whose new value is already shown by the Compose UI.
     */
    public static void suppressToastsFor(long duration_ms) {
        suppress_toasts_until = android.os.SystemClock.uptimeMillis() + duration_ms;
    }

    public static boolean suppressToast() {
        return ZERNO_UI && android.os.SystemClock.uptimeMillis() < suppress_toasts_until;
    }

    private static Listener getListener() {
        if( listener == null ) {
            listener = ru.inikonoff.zerno.adapter.ZernoBridge.INSTANCE;
        }
        return listener;
    }

    static void beforePreviewCreated(MainActivity activity, boolean first_run) {
        if( ZERNO_UI )
            getListener().onBeforePreviewCreated(activity, first_run);
    }

    static void activityCreated(MainActivity activity) {
        if( ZERNO_UI )
            getListener().onActivityCreated(activity);
    }

    static void cameraSetup(MainActivity activity) {
        if( ZERNO_UI )
            getListener().onCameraSetup(activity);
    }

    static void galleryThumbnail(MainActivity activity, Bitmap thumbnail) {
        if( ZERNO_UI )
            getListener().onGalleryThumbnail(activity, thumbnail);
    }

    static void settingsVisible(MainActivity activity, boolean visible) {
        if( ZERNO_UI )
            getListener().onSettingsVisible(activity, visible);
    }

    static void activityDestroyed(MainActivity activity) {
        if( ZERNO_UI )
            getListener().onActivityDestroyed(activity);
    }
}
