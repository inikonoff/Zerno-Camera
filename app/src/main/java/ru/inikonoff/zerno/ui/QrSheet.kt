package ru.inikonoff.zerno.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sourceforge.opencamera.R
import ru.inikonoff.zerno.adapter.QrContent
import ru.inikonoff.zerno.adapter.QrField
import ru.inikonoff.zerno.adapter.QrKind
import ru.inikonoff.zerno.adapter.QrWifi

private val SheetColor = Color(0xF21C1C1E)
private val FieldColor = Color(0x14FFFFFF)

@Composable
private fun kindName(kind: QrKind): String = stringResource(
    when (kind) {
        QrKind.WIFI -> R.string.zerno_qr_kind_wifi
        QrKind.LINK -> R.string.zerno_qr_kind_link
        QrKind.OTP -> R.string.zerno_qr_kind_otp
        QrKind.CONTACT -> R.string.zerno_qr_kind_contact
        QrKind.PHONE -> R.string.zerno_qr_kind_phone
        QrKind.EMAIL -> R.string.zerno_qr_kind_email
        QrKind.SMS -> R.string.zerno_qr_kind_sms
        QrKind.GEO -> R.string.zerno_qr_kind_geo
        QrKind.EVENT -> R.string.zerno_qr_kind_event
        QrKind.TEXT -> R.string.zerno_qr_kind_text
    }
)

/** The chip shown over the preview while a QR code is in view. Tapping it opens [QrSheet]. */
@Composable
fun QrChip(content: QrContent, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xB3000000))
            .tap(onClick = onClick)
            .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZIcon(ZernoIcon.QR, ZernoColors.Accent, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.widthIn(max = 240.dp)) {
            Label(kindName(content.kind), color = ZernoColors.Dim, size = 11.sp)
            BasicText(
                content.summary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/** Everything a QR code holds: each value on its own line with a copy button, plus the action
 *  that fits the kind of code (open the link, connect to the Wi-Fi network, ...).
 */
@Composable
fun QrSheet(content: QrContent, onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).tap(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(SheetColor)
                .tap {} // taps on the sheet itself don't close it
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZIcon(ZernoIcon.QR, ZernoColors.Accent, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                BasicText(
                    kindName(content.kind),
                    modifier = Modifier.weight(1f),
                    style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                )
                IconButton(ZernoIcon.CLOSE, stringResource(R.string.zerno_qr_close), ZernoColors.Dim, onClose)
            }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (field in content.fields) FieldRow(field, context)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val wifi = content.wifi
                val openUri = content.openUri
                if (wifi != null) {
                    ActionButton(stringResource(R.string.zerno_qr_connect), primary = true, Modifier.weight(1f)) { connectToWifi(context, wifi) }
                } else if (openUri != null) {
                    ActionButton(stringResource(R.string.zerno_qr_open), primary = true, Modifier.weight(1f)) { open(context, openUri) }
                }
                ActionButton(stringResource(R.string.zerno_qr_copy_all), primary = false, Modifier.weight(1f)) {
                    copy(context, content.raw, secret = content.fields.any { it.secret })
                }
            }
        }
    }
}

@Composable
private fun FieldRow(field: QrField, context: Context) {
    var revealed by remember(field) { mutableStateOf(!field.secret) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FieldColor).padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(stringResource(field.label), style = TextStyle(color = ZernoColors.Dim, fontSize = 12.sp))
            BasicText(
                if (revealed) field.value else "•".repeat(field.value.length.coerceIn(6, 16)),
                style = TextStyle(color = Color.White, fontSize = 15.sp, lineHeight = 20.sp),
            )
        }
        if (field.secret) {
            IconButton(ZernoIcon.EYE, stringResource(R.string.zerno_qr_show), if (revealed) ZernoColors.Accent else ZernoColors.Dim) { revealed = !revealed }
        }
        IconButton(ZernoIcon.COPY, stringResource(R.string.zerno_qr_copy), Color.White) { copy(context, field.value, field.secret) }
    }
}

@Composable
private fun IconButton(icon: ZernoIcon, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).semantics { contentDescription = description }.tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZIcon(icon, tint, Modifier.size(22.dp))
    }
}

@Composable
private fun ActionButton(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(50))
            .background(if (primary) ZernoColors.Accent else FieldColor)
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            maxLines = 1,
            style = TextStyle(color = if (primary) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

private fun copy(context: Context, text: String, secret: Boolean) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("QR", text)
    if (secret) {
        // keeps passwords and keys out of the clipboard preview (Android 13+)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    clipboard.setPrimaryClip(clip)
    // Android 13+ shows its own confirmation
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.zerno_qr_copied, Toast.LENGTH_SHORT).show()
    }
}

private fun open(context: Context, uri: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.zerno_qr_no_app, Toast.LENGTH_SHORT).show()
    }
}

/** Hands the network to the system's "add network" dialog (Android 11+). On older versions,
 *  or for kinds of network that dialog can't take, the password is copied and the Wi-Fi
 *  settings are opened instead.
 */
private fun connectToWifi(context: Context, wifi: QrWifi) {
    try {
        val encryption = wifi.encryption?.uppercase() ?: ""
        val supported = encryption.isEmpty() || encryption == "NOPASS" || encryption.startsWith("WPA") || encryption == "SAE"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && supported && wifi.ssid.isNotEmpty()) {
            val suggestion = WifiNetworkSuggestion.Builder().setSsid(wifi.ssid).setIsHiddenSsid(wifi.hidden).apply {
                val password = wifi.password
                if (password != null) {
                    if (encryption == "SAE") setWpa3Passphrase(password) else setWpa2Passphrase(password)
                }
            }.build()
            val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }
    } catch (e: RuntimeException) {
        // fall through to the Wi-Fi settings
    }
    wifi.password?.let { copy(context, it, secret = true) }
    try {
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.zerno_qr_no_app, Toast.LENGTH_SHORT).show()
    }
}
