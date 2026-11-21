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

    // ---- night mode, stabilisation ----

    /** Frames in a Night burst: 0 = let the engine decide (3, 4 or 8, by how dark it is). */
    public static volatile int night_frames;
    /** Zerno: Night tuning. Median: 0 off, 1 weak, 2 engine default. */
    public static volatile int night_median = 2;
    /** Zerno: stricter merge tolerance (Wiener C quartered) so moving/misaligned detail is not averaged away. */
    public static volatile boolean night_strict;
    /** Zerno: skip the engine's histogram redistribution after the merge. */
    public static volatile boolean night_natural_tone;

    public static float nightMedianFactor() {
        if( !ZERNO_UI )
            return 1.0f;
        switch( night_median ) {
            case 0: return 0.0f;
            case 1: return 0.4f;
            default: return 1.0f;
        }
    }

    public static float nightWienerScale() {
        return ( ZERNO_UI && night_strict ) ? 0.25f : 1.0f;
    }

    /** Optical image stabilisation switched off by the user (only matters if the camera has it). */
    public static volatile boolean ois_disabled;

    // ---- ISO / shutter priority ----

    /** While ISO or shutter priority is in use, the preview runs on the camera's auto exposure,
     *  and the adapter works out the manual ISO and exposure time to take the photo with. 0 = no
     *  override (take the photo as the preview is set up).
     */
    public static volatile int still_iso;
    public static volatile long still_exposure_time;

    /** Called with the request for a single photo, just before it is sent to the camera. */
    public static void applyStillOverride(android.hardware.camera2.CaptureRequest.Builder builder) {
        if( !ZERNO_UI )
            return;
        final int iso = still_iso;
        final long exposure_time = still_exposure_time;
        if( iso > 0 && exposure_time > 0 ) {
            builder.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF);
            builder.set(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, iso);
            builder.set(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, exposure_time);
            if( android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O ) {
                // zero shutter lag would hand back a frame from before the press, taken with the preview's exposure
                builder.set(android.hardware.camera2.CaptureRequest.CONTROL_ENABLE_ZSL, false);
            }
        }
        // With manual exposure, the time between frames must be at least the exposure time. Most
        // cameras stretch it by themselves, but some keep the frame time the auto exposure was
        // using (e.g. 1/3s) and cut the exposure short to fit - so ask for it explicitly.
        Integer ae_mode = builder.get(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE);
        Long requested_exposure = builder.get(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME);
        if( ae_mode != null && ae_mode == android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF && requested_exposure != null ) {
            Long frame = builder.get(android.hardware.camera2.CaptureRequest.SENSOR_FRAME_DURATION);
            if( frame == null || frame < requested_exposure )
                builder.set(android.hardware.camera2.CaptureRequest.SENSOR_FRAME_DURATION, requested_exposure);
            Integer requested_iso = builder.get(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY);
            last_still_request = "manual: ISO " + requested_iso + ", exposure " + formatNs(requested_exposure) +
                    ", frame " + formatNs(builder.get(android.hardware.camera2.CaptureRequest.SENSOR_FRAME_DURATION));
        }
        else {
            last_still_request = "auto exposure";
        }
        last_still_result = "-";
    }

    /** What the last photo was asked for, and what the camera reports it did (for diagnostics). */
    public static volatile String last_still_request = "-";
    public static volatile String last_still_result = "-";

    private static String formatNs(Long ns) {
        if( ns == null )
            return "-";
        if( ns >= 300000000L )
            return String.format(java.util.Locale.US, "%.1fs", ns / 1.0e9);
        return "1/" + Math.round(1.0e9 / ns) + "s";
    }

    // ---- white balance ----

    /** White balance gains (R, G_even, G_odd, B) measured from a grey card, used while the white
     *  balance is "manual"; null to use the colour temperature instead.
     */
    public static volatile float [] custom_wb_gains;

    /** The white balance gains of the latest preview frame. */
    public static volatile float [] last_wb_gains;

    /** Called (on a camera thread) for each capture result. */
    public static void captureResult(android.hardware.camera2.CaptureResult result) {
        if( !ZERNO_UI )
            return;
        try {
            // a photo (not a preview frame): record what the camera actually did
            Integer intent = result.get(android.hardware.camera2.CaptureResult.CONTROL_CAPTURE_INTENT);
            if( intent != null && intent != android.hardware.camera2.CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW ) {
                last_still_result = "ISO " + result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) +
                        ", exposure " + formatNs(result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)) +
                        ", frame " + formatNs(result.get(android.hardware.camera2.CaptureResult.SENSOR_FRAME_DURATION));
            }
        }
        catch(RuntimeException e) {
            // not reported
        }
        try {
            android.hardware.camera2.params.RggbChannelVector gains = result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS);
            if( gains != null )
                last_wb_gains = new float[]{gains.getRed(), gains.getGreenEven(), gains.getGreenOdd(), gains.getBlue()};
        }
        catch(RuntimeException e) {
            // not reported by this camera
        }
    }

    // ---- image adjustment ----

    /** A 4x5 colour matrix (as used by android.graphics.ColorMatrix) applied to every saved
     *  photo, or null for no adjustment. Set by the adapter from the brightness / contrast /
     *  saturation settings. This is the current setting; each photo gets its own copy (see Shot).
     */
    public static volatile float [] color_matrix;

    /** Strength of the colour noise removal applied to every saved photo: 0 (off) to
     *  ZernoChromaDenoise.MAX_LEVEL.
     */
    public static volatile int chroma_denoise;

    /** Sharpness applied to every saved photo: -5 (soft) to +5 (sharp); 0 = none. */
    public static volatile int sharpness;

    /** Applies the colour noise removal and color_matrix to the photo. If there is nothing to
     *  do, returns the supplied bitmap (which may be null, meaning that the JPEG data is saved
     *  untouched). Works in place, in bands, so that no second full-size bitmap is needed.
     *  The settings come from the Shot taken when the photo was captured, not from the current
     *  settings, which may have been changed while the photo waited in the save queue.
     */
    static Bitmap adjustImage(Shot shot, byte [] data, Bitmap bitmap) {
        final float [] matrix = shot.color_matrix;
        final int chroma_level = shot.chroma_denoise;
        final int sharp_level = shot.sharpness;
        if( !ZERNO_UI || ( matrix == null && chroma_level <= 0 && sharp_level == 0 ) )
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
            if( sharp_level != 0 ) {
                sharpen(bitmap, sharp_level);
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

    /** Sharpens or softens the bitmap in place (see ZernoSharpen), on several threads. Each band
     *  is given the original rows just above and below it, saved beforehand, so that the result
     *  doesn't depend on the order the bands are processed in.
     */
    private static void sharpen(final Bitmap bitmap, int level) {
        final int width = bitmap.getWidth(), height = bitmap.getHeight();
        final ZernoSharpen sharpen = new ZernoSharpen(width, height, level);
        final int halo = sharpen.halo();
        final int band_height = 128;
        final int n_bands = (height + band_height - 1) / band_height;
        final int [][] above = new int[n_bands][];
        final int [][] below = new int[n_bands][];
        for(int band=0;band<n_bands;band++) {
            int y = band*band_height;
            int rows = Math.min(band_height, height - y);
            if( band > 0 ) {
                above[band] = new int[width*halo];
                bitmap.getPixels(above[band], 0, width, 0, y - halo, width, halo);
            }
            if( band < n_bands - 1 ) {
                below[band] = new int[width*halo];
                bitmap.getPixels(below[band], 0, width, 0, y + rows, width, halo);
            }
        }
        runInBands(bitmap, band_height, true, new BandTask() {
            @Override
            public void run(int [] pixels, int y, int rows) {
                int band = y / band_height;
                sharpen.apply(pixels, rows, above[band], above[band] != null ? halo : 0, below[band], below[band] != null ? halo : 0);
            }
        });
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

    // ---- settings ----

    /** Open Camera settings that make no sense in Zerno: video, the stock on-screen controls
     *  (which are hidden), and settings that the Zerno UI controls itself (so that nothing has
     *  two places to be set).
     */
    private static final java.util.Set<String> hidden_preferences = new java.util.HashSet<>(java.util.Arrays.asList(
            // video
            "preference_screen_video_settings", "preference_save_video_prefix", "preference_show_video_max_amp",
            "preference_camera2_photo_video_recording",
            // needs the microphone, which Zerno doesn't ask for
            "preference_audio_control", "preference_audio_noise_control_sensitivity",
            // controlled by the Zerno UI
            "preference_timer", "preference_edge_mode", "preference_noise_reduction_mode", "preference_grid",
            "preference_show_angle_line", "preference_focus_peaking", "preference_focus_peaking_color",
            "preference_camera_api", "preference_lock_orientation", "preference_thumbnail_animation",
            // photo modes that Zerno doesn't offer
            "preference_expo_bracketing_n_images", "preference_expo_bracketing_stops", "preference_raw_expo_bracketing",
            "preference_raw_focus_bracketing", "preference_panorama_crop", "preference_panorama_save",
            // the stock on-screen read-outs and buttons, which aren't shown
            "preference_show_zoom", "preference_show_angle", "preference_show_battery", "preference_show_time", "preference_show_camera_id",
            "preference_free_memory", "preference_show_iso", "preference_histogram",
            "preference_ui_placement", "preference_immersive_mode", "preference_show_face_detection", "preference_show_cycle_flash",
            "preference_show_focus_peaking", "preference_show_auto_level", "preference_show_stamp", "preference_show_textstamp",
            "preference_show_store_location", "preference_show_cycle_raw", "preference_show_white_balance_lock",
            "preference_show_exposure_lock", "preference_show_cycle_lock_orientation", "preference_show_preview_shots",
            "preference_show_zoom_slider_controls", "preference_show_take_photo", "preference_show_whats_new", "preference_multi_cam_button"
    ));

    /** Removes the hidden preferences from a settings screen (and its sub-groups). */
    static void prunePreferences(android.preference.PreferenceGroup group) {
        if( !ZERNO_UI || group == null )
            return;
        for(int i=group.getPreferenceCount()-1;i>=0;i--) {
            android.preference.Preference pref = group.getPreference(i);
            if( pref.getKey() != null && hidden_preferences.contains(pref.getKey()) ) {
                group.removePreference(pref);
            }
            else if( pref instanceof android.preference.PreferenceGroup ) {
                prunePreferences((android.preference.PreferenceGroup)pref);
            }
        }
    }

    // ---- exif ----

    /** The settings that are applied when a photo is saved (which can be some time after it was
     *  taken - several photos can be waiting in the save queue). They are copied when the photo is
     *  captured, so that changing a setting afterwards doesn't alter photos already taken, and so
     *  that what is written to the photo's EXIF is what was really applied.
     *  Immutable (the matrix array is never modified after it is copied).
     */
    static final class Shot {
        final float [] color_matrix;
        final int chroma_denoise;
        final int sharpness;
        /** One line describing Zerno's settings (plain ASCII), for the ImageDescription tag; null if unknown. */
        final String exif_description;
        final String exif_software;
        final long time;

        Shot(float [] color_matrix, int chroma_denoise, int sharpness, String exif_description, String exif_software, long time) {
            this.color_matrix = color_matrix;
            this.chroma_denoise = chroma_denoise;
            this.sharpness = sharpness;
            this.exif_description = exif_description;
            this.exif_software = exif_software;
            this.time = time;
        }
    }

    /** How long a Shot prepared at a shutter press stays valid, if the capture never starts. */
    private static final long PENDING_SHOT_MS = 30000;
    private static volatile Shot pending_shot; // prepared by the UI at the shutter press
    private static volatile Shot current_shot; // the photo that is being taken

    private static Shot snapshot(String exif_description) {
        final float [] matrix = color_matrix;
        return new Shot(matrix != null ? matrix.clone() : null, chroma_denoise, sharpness, exif_description,
                "Zerno " + BuildConfig.VERSION_NAME, android.os.SystemClock.uptimeMillis());
    }

    /** Called by the adapter when the shutter is pressed: copies the current settings, together
     *  with the description of them to write to the photo's EXIF (plain ASCII).
     */
    public static void beginShot(String exif_description) {
        pending_shot = snapshot(exif_description);
    }

    /** Called when the camera starts the exposure: the prepared Shot (or, if the photo was not
     *  started from the Zerno UI - e.g. with a volume key - a fresh copy of the current
     *  settings, without an EXIF description) becomes the Shot of the photo being taken.
     */
    static void captureStarted() {
        final Shot pending = pending_shot;
        pending_shot = null;
        if( pending != null && android.os.SystemClock.uptimeMillis() - pending.time <= PENDING_SHOT_MS )
            current_shot = pending;
        else
            current_shot = snapshot(null);
    }

    /** The Shot to attach to a new save request. */
    static Shot currentShot() {
        final Shot shot = current_shot;
        return shot != null ? shot : snapshot(null);
    }

    static boolean hasCustomExif(Shot shot) {
        return ZERNO_UI && shot != null && shot.exif_description != null;
    }

    static void customExif(androidx.exifinterface.media.ExifInterface exif, Shot shot) {
        if( !ZERNO_UI || shot == null )
            return;
        if( shot.exif_description != null )
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_DESCRIPTION, shot.exif_description);
        if( shot.exif_software != null )
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE, shot.exif_software);
    }

    // ---- check of the saved file (diagnostics) ----

    /** What the check of the last saved photo found (for the diagnostics report). */
    public static volatile String last_save_check = "-";
    /** How many saved photos have failed the check since the app started. */
    public static volatile int save_check_failures;

    /** Checks the photo that has just been written (and its EXIF updated): that the file is not
     *  empty, that its header can be decoded to a size and - for a JPEG - that it ends with the
     *  end-of-image marker, i.e. was not cut short. Only records the result (see
     *  last_save_check); the save is not affected, whatever the outcome. Never throws.
     */
    static void verifySaved(android.content.Context context, java.io.File file, android.net.Uri uri, boolean jpeg) {
        if( !ZERNO_UI )
            return;
        String result;
        boolean failed = false;
        android.os.ParcelFileDescriptor pfd = null;
        try {
            if( file != null )
                pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
            else if( uri != null )
                pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            if( pfd == null ) {
                result = "not checked: file could not be opened";
            }
            else {
                final java.io.FileInputStream stream = new java.io.FileInputStream(pfd.getFileDescriptor());
                final java.nio.channels.FileChannel channel = stream.getChannel();
                final long size = channel.size();
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, options);
                if( size <= 0 ) {
                    result = "FAILED: empty file";
                    failed = true;
                }
                else if( options.outWidth <= 0 || options.outHeight <= 0 ) {
                    result = "FAILED: header not readable, " + size + " bytes";
                    failed = true;
                }
                else {
                    boolean complete = true;
                    if( jpeg && size >= 2 ) {
                        java.nio.ByteBuffer tail = java.nio.ByteBuffer.allocate(2);
                        int n = 0;
                        while( n < 2 ) {
                            int read = channel.read(tail, size - 2 + n);
                            if( read < 0 )
                                break;
                            n += read;
                        }
                        complete = n == 2 && ( tail.get(0) & 0xff ) == 0xff && ( tail.get(1) & 0xff ) == 0xd9;
                    }
                    if( complete ) {
                        result = "ok, " + options.outWidth + "x" + options.outHeight + ", " + size + " bytes";
                    }
                    else {
                        result = "FAILED: file cut short (no end marker), " + options.outWidth + "x" + options.outHeight + ", " + size + " bytes";
                        failed = true;
                    }
                }
            }
        }
        catch(Exception e) {
            result = "not checked: " + e.getClass().getSimpleName();
        }
        finally {
            if( pfd != null ) {
                try {
                    pfd.close();
                }
                catch(java.io.IOException e) {
                    // nothing to do
                }
            }
        }
        if( failed ) {
            save_check_failures++;
            MyDebug.logStackTrace("ZernoHooks", "saved photo failed the check: " + result, new RuntimeException(result));
        }
        last_save_check = result;
    }

    // ---- touch focus (diagnostics) ----

    public static volatile int touch_events; // touch events that reached the engine's preview view
    public static volatile int zerno_taps; // taps passed on by the Zerno UI
    public static volatile String last_touch_focus = "none";

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
