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
