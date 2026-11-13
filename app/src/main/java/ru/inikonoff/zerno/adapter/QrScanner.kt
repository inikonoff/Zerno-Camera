package ru.inikonoff.zerno.adapter

import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import androidx.annotation.StringRes
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.client.result.AddressBookParsedResult
import com.google.zxing.client.result.CalendarParsedResult
import com.google.zxing.client.result.EmailAddressParsedResult
import com.google.zxing.client.result.GeoParsedResult
import com.google.zxing.client.result.ResultParser
import com.google.zxing.client.result.SMSParsedResult
import com.google.zxing.client.result.TelParsedResult
import com.google.zxing.client.result.URIParsedResult
import com.google.zxing.client.result.WifiParsedResult
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.sourceforge.opencamera.R
import java.util.concurrent.atomic.AtomicBoolean

enum class QrKind { WIFI, LINK, OTP, CONTACT, PHONE, EMAIL, SMS, GEO, EVENT, TEXT }

/** One value from a QR code, shown on its own line with a "copy" button. Secret values
 *  (passwords, keys) are masked until revealed, and are marked as sensitive on the clipboard.
 */
data class QrField(@StringRes val label: Int, val value: String, val secret: Boolean = false)

data class QrWifi(val ssid: String, val password: String?, val encryption: String?, val hidden: Boolean)

data class QrContent(
    val kind: QrKind,
    /** One line describing the code, for the chip shown over the preview. */
    val summary: String,
    val fields: List<QrField>,
    /** The complete text of the code, exactly as encoded. */
    val raw: String,
    /** What "open" should launch (a link, tel:, mailto:, geo:, otpauth: ...), if anything. */
    val openUri: String? = null,
    val wifi: QrWifi? = null,
)

/** Finds and decodes QR codes in the camera preview (ZXing). */
class QrScanner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val busy = AtomicBoolean(false)
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)

    /** Grabs the current preview frame (must be called on the main thread) and looks for a QR
     *  code in it on a background thread. [onFound] is called from that thread. Does nothing if
     *  the previous frame is still being examined.
     */
    fun scan(view: TextureView, onFound: (QrContent) -> Unit) {
        if (view.width == 0 || view.height == 0) return
        if (!busy.compareAndSet(false, true)) return
        val width = FRAME_WIDTH
        val height = (width.toLong() * view.height / view.width).toInt().coerceIn(width / 2, width * 3)
        var frame = bitmap
        if (frame == null || frame.width != width || frame.height != height) {
            frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = frame
            pixels = IntArray(width * height)
        }
        try {
            view.getBitmap(frame)
        } catch (e: RuntimeException) {
            busy.set(false)
            return
        }
        val target = frame
        scope.launch {
            try {
                target.getPixels(pixels, 0, width, 0, 0, width, height)
                decode(width, height)?.let { onFound(describe(it)) }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun decode(width: Int, height: Int): Result? {
        val source = RGBLuminanceSource(width, height, pixels)
        // codes are usually dark on light, but try light on dark too
        for (candidate in listOf(source, source.invert())) {
            try {
                return reader.decode(BinaryBitmap(HybridBinarizer(candidate)), hints)
            } catch (e: ReaderException) {
                // no code in this frame
            } finally {
                reader.reset()
            }
        }
        return null
    }

    companion object {
        private const val FRAME_WIDTH = 800

        /** Works out what kind of thing a decoded code holds, and splits it into its values. */
        fun describe(result: Result): QrContent {
            val raw = result.text ?: ""
            fun MutableList<QrField>.add(label: Int, value: String?, secret: Boolean = false) {
                if (!value.isNullOrBlank()) add(QrField(label, value.trim(), secret))
            }
            return when (val parsed = ResultParser.parseResult(result)) {
                is WifiParsedResult -> {
                    val open = parsed.networkEncryption.isNullOrBlank() || parsed.networkEncryption.equals("nopass", true)
                    QrContent(
                        QrKind.WIFI, parsed.ssid ?: "Wi-Fi",
                        buildList {
                            add(R.string.zerno_qr_wifi_name, parsed.ssid)
                            add(R.string.zerno_qr_password, parsed.password, secret = true)
                            if (!open) add(R.string.zerno_qr_wifi_security, parsed.networkEncryption)
                        },
                        raw,
                        wifi = QrWifi(parsed.ssid ?: "", parsed.password?.takeIf { it.isNotEmpty() }, parsed.networkEncryption, parsed.isHidden),
                    )
                }
                is URIParsedResult -> {
                    val uri = parsed.uri ?: raw
                    if (uri.startsWith("otpauth://", ignoreCase = true)) describeOtp(uri, raw)
                    else QrContent(
                        QrKind.LINK, Uri.parse(uri).host ?: uri,
                        buildList {
                            add(R.string.zerno_qr_title, parsed.title)
                            add(R.string.zerno_qr_link, uri)
                        },
                        raw, openUri = uri,
                    )
                }
                is TelParsedResult -> QrContent(
                    QrKind.PHONE, parsed.number ?: raw,
                    buildList { add(R.string.zerno_qr_phone, parsed.number) },
                    raw, openUri = parsed.telURI,
                )
                is EmailAddressParsedResult -> QrContent(
                    QrKind.EMAIL, parsed.tos?.firstOrNull() ?: raw,
                    buildList {
                        add(R.string.zerno_qr_email, parsed.tos?.joinToString(", "))
                        add(R.string.zerno_qr_subject, parsed.subject)
                        add(R.string.zerno_qr_text, parsed.body)
                    },
                    raw, openUri = if (raw.startsWith("mailto:", true)) raw else parsed.tos?.firstOrNull()?.let { "mailto:$it" },
                )
                is SMSParsedResult -> QrContent(
                    QrKind.SMS, parsed.numbers?.firstOrNull() ?: raw,
                    buildList {
                        add(R.string.zerno_qr_phone, parsed.numbers?.joinToString(", "))
                        add(R.string.zerno_qr_text, parsed.body)
                    },
                    raw, openUri = parsed.smsuri,
                )
                is GeoParsedResult -> QrContent(
                    QrKind.GEO, "${parsed.latitude}, ${parsed.longitude}",
                    buildList { add(R.string.zerno_qr_coordinates, "${parsed.latitude}, ${parsed.longitude}") },
                    raw, openUri = parsed.geoURI,
                )
                is AddressBookParsedResult -> QrContent(
                    QrKind.CONTACT, parsed.names?.firstOrNull() ?: parsed.org ?: raw.take(40),
                    buildList {
                        add(R.string.zerno_qr_name, parsed.names?.joinToString(", "))
                        add(R.string.zerno_qr_organisation, parsed.org)
                        add(R.string.zerno_qr_title, parsed.title)
                        parsed.phoneNumbers?.forEach { add(R.string.zerno_qr_phone, it) }
                        parsed.emails?.forEach { add(R.string.zerno_qr_email, it) }
                        parsed.addresses?.forEach { add(R.string.zerno_qr_address, it) }
                        parsed.urLs?.forEach { add(R.string.zerno_qr_link, it) }
                        add(R.string.zerno_qr_note, parsed.note)
                    },
                    raw,
                )
                is CalendarParsedResult -> QrContent(
                    QrKind.EVENT, parsed.summary ?: raw.take(40),
                    buildList {
                        add(R.string.zerno_qr_event, parsed.summary)
                        add(R.string.zerno_qr_address, parsed.location)
                        add(R.string.zerno_qr_text, parsed.description)
                    },
                    raw,
                )
                else -> QrContent(
                    QrKind.TEXT, raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: raw,
                    buildList { add(R.string.zerno_qr_text, raw) },
                    raw,
                )
            }
        }

        /** otpauth:// codes carry the secret key of a one-time password (2FA) account. */
        private fun describeOtp(uri: String, raw: String): QrContent {
            val parsed = Uri.parse(uri)
            val label = parsed.path?.trimStart('/') ?: ""
            val issuer = parsed.getQueryParameterOrNull("issuer") ?: label.substringBefore(':', "").ifBlank { null }
            val account = label.substringAfter(':').trim()
            return QrContent(
                QrKind.OTP, issuer ?: account.ifBlank { "OTP" },
                buildList {
                    if (!issuer.isNullOrBlank()) add(QrField(R.string.zerno_qr_otp_issuer, issuer))
                    if (account.isNotBlank()) add(QrField(R.string.zerno_qr_otp_account, account))
                    parsed.getQueryParameterOrNull("secret")?.let { add(QrField(R.string.zerno_qr_otp_secret, it, secret = true)) }
                },
                raw, openUri = uri,
            )
        }

        private fun Uri.getQueryParameterOrNull(name: String): String? = try {
            getQueryParameter(name)?.takeIf { it.isNotBlank() }
        } catch (e: UnsupportedOperationException) {
            null
        }
    }
}
