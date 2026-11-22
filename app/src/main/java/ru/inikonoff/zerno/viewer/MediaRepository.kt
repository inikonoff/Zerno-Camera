package ru.inikonoff.zerno.viewer

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.sourceforge.opencamera.R

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    /** RAW (DNG) files saved with this photo; they follow it when it is trashed or deleted. */
    val companions: List<MediaItem> = emptyList(),
    /** The other frames of a series (a burst or a focus series, saved as IMG_..._0, _1, ...) shown
     *  as one photo: this item is the first frame. They follow it when it is trashed or deleted.
     */
    val series: List<MediaItem> = emptyList(),
) {
    val isRaw: Boolean get() = name.endsWith(".dng", ignoreCase = true)
}

private fun List<MediaItem>.withCompanions(): List<MediaItem> =
    flatMap { item -> listOf(item) + item.companions + item.series.flatMap { listOf(it) + it.companions } }

private val SERIES_NAME = Regex("^(.+)_(\\d{1,3})(\\.[^.]+)$")

/** Frames saved by one burst share a name up to a number at the end ("IMG_20260101_120000_0.jpg",
 *  "..._1.jpg"): they become one item, whose [MediaItem.series] holds the rest. A lone numbered
 *  file stays as it is. The first frame (lowest number) stands for the series, at the place of
 *  the series' newest file in the list.
 */
internal fun groupSeries(items: List<MediaItem>): List<MediaItem> {
    fun key(item: MediaItem): String? =
        SERIES_NAME.matchEntire(item.name)?.let { it.groupValues[1] + it.groupValues[3].lowercase() }
    fun number(item: MediaItem): Int = SERIES_NAME.matchEntire(item.name)!!.groupValues[2].toInt()

    val byKey = items.mapNotNull { item -> key(item)?.let { it to item } }.groupBy({ it.first }, { it.second })
    val emitted = HashSet<String>()
    val result = ArrayList<MediaItem>()
    for (item in items) {
        val k = key(item)
        val group = k?.let { byKey[it] }
        if (k == null || group == null || group.size < 2) {
            result.add(item)
        } else if (emitted.add(k)) {
            val frames = group.sortedBy { number(it) }
            result.add(frames.first().copy(series = frames.drop(1)))
        }
    }
    return result
}

/** The outcome of moving photos to/from the trash or deleting them. */
sealed interface ChangeResult {
    data object Done : ChangeResult
    /** Android wants the user to confirm (the photos were not created by this installation). */
    data class NeedsConsent(val intent: IntentSender) : ChangeResult
    data object Failed : ChangeResult
}

/** One line of the info panel: what it is (a string resource, or a literal label) and its value. */
data class InfoLine(val label: Int, val value: String)

/** Access to the photos in one folder - the folder Zerno saves to - through MediaStore. The
 *  viewer never looks anywhere else.
 */
class MediaRepository(private val context: Context, folderPath: String) {
    private val resolver: ContentResolver = context.contentResolver
    private val folder = folderPath.trimEnd('/')

    /** The folder as MediaStore's RELATIVE_PATH (e.g. "DCIM/OpenCamera/"), for Android 10+. */
    private val relativePath: String? = run {
        val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        if (folder.startsWith("$root/")) folder.removePrefix("$root/") + "/" else null
    }

    /** The trash needs Android 11; before that, deleting is permanent. */
    val hasTrash: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private val collection: Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** The photos in the folder, newest first; with [trashed], those in the trash instead. */
    fun load(trashed: Boolean): List<MediaItem> {
        if (trashed && !hasTrash) return emptyList()
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
        )
        val items = ArrayList<MediaItem>()
        try {
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && relativePath != null) {
                val args = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(relativePath))
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC")
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
                }
                resolver.query(collection, projection, args, null)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null) {
                resolver.query(
                    collection, projection, "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf(relativePath),
                    "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
                )
            } else {
                @Suppress("DEPRECATION")
                resolver.query(
                    collection, projection,
                    "${MediaStore.Images.Media.DATA} LIKE ? AND ${MediaStore.Images.Media.DATA} NOT LIKE ?", arrayOf("$folder/%", "$folder/%/%"),
                    "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
                )
            }
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val taken = it.getLong(3)
                    items.add(
                        MediaItem(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            name = (it.getString(1) ?: "").let { name -> if (trashed) originalName(name) else name },
                            dateMs = if (taken > 0) taken else it.getLong(2) * 1000L,
                            sizeBytes = it.getLong(4),
                            width = it.getInt(5),
                            height = it.getInt(6),
                        )
                    )
                }
            }
        } catch (e: RuntimeException) {
            // no access: show nothing
        }
        return groupSeries(pairWithRaw(items))
    }

    /** A RAW file saved next to a photo of the same name isn't listed on its own: it belongs to
     *  the photo.
     */
    private fun pairWithRaw(items: List<MediaItem>): List<MediaItem> {
        fun base(name: String) = name.substringBeforeLast('.')
        val photoBases = items.filter { !it.isRaw }.map { base(it.name) }.toSet()
        val rawByBase = items.filter { it.isRaw }.groupBy { base(it.name) }
        return items
            .filter { !it.isRaw || base(it.name) !in photoBases }
            .map { if (it.isRaw) it else it.copy(companions = rawByBase[base(it.name)].orEmpty()) }
    }

    /** Trashed files are renamed ".trashed-<expiry time>-<original name>". */
    private fun originalName(name: String): String =
        Regex("^\\.trashed-\\d+-(.+)$").find(name)?.groupValues?.get(1) ?: name

    /** Moves photos to the trash, or back out of it. */
    fun setTrashed(items: List<MediaItem>, trashed: Boolean): ChangeResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ChangeResult.Failed
        return try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0) }
            for (item in items.withCompanions()) resolver.update(item.uri, values, null, null)
            ChangeResult.Done
        } catch (e: SecurityException) {
            consent { MediaStore.createTrashRequest(resolver, items.withCompanions().map { it.uri }, trashed).intentSender }
        } catch (e: RuntimeException) {
            ChangeResult.Failed
        }
    }

    /** Deletes photos for good. */
    fun delete(items: List<MediaItem>): ChangeResult {
        return try {
            for (item in items.withCompanions()) resolver.delete(item.uri, null, null)
            ChangeResult.Done
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                consent { MediaStore.createDeleteRequest(resolver, items.withCompanions().map { it.uri }).intentSender }
            } else ChangeResult.Failed
        } catch (e: RuntimeException) {
            ChangeResult.Failed
        }
    }

    private inline fun consent(request: () -> IntentSender): ChangeResult = try {
        ChangeResult.NeedsConsent(request())
    } catch (e: RuntimeException) {
        ChangeResult.Failed
    }

    /** Decodes a photo, scaled down (by a power of two) so that neither side is much over
     *  [maxSide] pixels, and turned upright according to its EXIF orientation. Call off the
     *  main thread.
     */
    fun loadBitmap(item: MediaItem, maxSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = resolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val matrix = orientationMatrix(item) ?: return bitmap
            val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (turned != bitmap) bitmap.recycle()
            turned
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun orientationMatrix(item: MediaItem): Matrix? {
        val orientation = resolver.openInputStream(item.uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: return null
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return null
        }
        return matrix
    }

    /** What the info panel shows: the file, how it was shot (EXIF), and Zerno's settings. */
    fun info(item: MediaItem): List<InfoLine> {
        val lines = ArrayList<InfoLine>()
        fun add(label: Int, value: String?) {
            if (!value.isNullOrBlank()) lines.add(InfoLine(label, value.trim()))
        }
        add(R.string.zerno_info_file, item.name)
        if (item.companions.isNotEmpty()) add(R.string.zerno_info_raw, "DNG")
        add(R.string.zerno_info_date, SimpleDateFormat("d MMMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(item.dateMs)))
        try {
            resolver.openInputStream(item.uri)?.use { stream ->
                val exif = ExifInterface(stream)
                var width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 } ?: item.width
                var height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 } ?: item.height
                if (width > 0 && height > 0) {
                    if (exif.rotationDegrees % 180 != 0) width = height.also { height = width }
                    add(R.string.zerno_info_size, "$width × $height (%.1f Mp), %.1f MB".format(width * height / 1e6, item.sizeBytes / 1048576.0))
                }
                add(R.string.zerno_info_camera, listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" "))
                add(R.string.zerno_info_iso, exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
                exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let {
                    add(R.string.zerno_info_shutter, if (it >= 0.4) "%.1f s".format(it) else "1/${Math.round(1.0 / it)} s")
                }
                exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { add(R.string.zerno_info_aperture, "f/%.1f".format(it)) }
                exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { add(R.string.zerno_info_focal, "%.1f mm".format(it)) }
                exif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)?.let {
                    val ev = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, 0.0)
                    if (ev != 0.0) add(R.string.zerno_info_ev, "%+.1f EV".format(ev))
                }
                val flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1)
                if (flash >= 0) add(R.string.zerno_info_flash, context.getString(if (flash and 1 == 1) R.string.zerno_info_yes else R.string.zerno_info_no))
                exif.latLong?.let { add(R.string.zerno_info_location, "%.5f, %.5f".format(Locale.US, it[0], it[1])) }
                add(R.string.zerno_info_software, exif.getAttribute(ExifInterface.TAG_SOFTWARE))
                // Zerno's own settings, written by the camera into ImageDescription
                val description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
                if (description != null && description.startsWith(ZERNO_PREFIX)) {
                    for (part in description.removePrefix(ZERNO_PREFIX).split(';')) {
                        val key = part.substringBefore('=').trim()
                        val value = part.substringAfter('=', "").trim()
                        val label = ZERNO_LABELS[key] ?: continue
                        add(label, value)
                    }
                }
            }
        } catch (e: Exception) {
            // unreadable EXIF: the file lines above still show
        }
        return lines
    }

    companion object {
        /** How Zerno's settings line in the ImageDescription tag starts. */
        const val ZERNO_PREFIX = "Zerno: "

        private val ZERNO_LABELS = mapOf(
            "mode" to R.string.zerno_info_mode,
            "detail" to R.string.zerno_info_detail,
            "chroma" to R.string.zerno_info_chroma,
            "sharpness" to R.string.zerno_info_sharpness,
            "frames" to R.string.zerno_info_frames,
            "median" to R.string.zerno_night_median,
            "merge" to R.string.zerno_night_merge,
            "tone" to R.string.zerno_night_tone,
            "hdr" to R.string.zerno_info_hdr,
            "shadows" to R.string.zerno_info_shadows,
            "highlights" to R.string.zerno_info_highlights,
            "adjust" to R.string.zerno_info_adjust,
            "metering" to R.string.zerno_info_metering,
            "focus" to R.string.zerno_info_focus,
            "zoom" to R.string.zerno_info_zoom,
        )

        /** The folder a file path belongs to (used on old Android versions only). */
        @Suppress("unused")
        fun parentOf(path: String): String = File(path).parent ?: path
    }
}
