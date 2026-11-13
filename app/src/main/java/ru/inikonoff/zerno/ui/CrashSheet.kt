package ru.inikonoff.zerno.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.sourceforge.opencamera.R

/** Shown on the first start after a crash: what went wrong, with buttons to copy or share the
 *  report.
 */
@Composable
fun CrashSheet(report: String, onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000)).tap {}.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF1C1C1E)).padding(18.dp)) {
            BasicText(
                stringResource(R.string.zerno_crash_title),
                style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(stringResource(R.string.zerno_crash_text), style = TextStyle(color = ZernoColors.Dim, fontSize = 13.sp, lineHeight = 18.sp))
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF))
                    .verticalScroll(rememberScrollState()).padding(10.dp)
            ) {
                BasicText(report, style = TextStyle(color = Color.White, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton(stringResource(R.string.zerno_qr_copy), true, Modifier.weight(1f)) { copyReport(context, report) }
                SheetButton(stringResource(R.string.zerno_crash_share), false, Modifier.weight(1f)) { shareReport(context, report) }
                SheetButton(stringResource(R.string.zerno_qr_close), false, Modifier.weight(1f), onClose)
            }
        }
    }
}

@Composable
private fun SheetButton(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(50)).background(if (primary) ZernoColors.Accent else Color(0x14FFFFFF)).tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, maxLines = 1, style = TextStyle(color = if (primary) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
    }
}

private fun copyReport(context: Context, report: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Zerno crash", report))
}

private fun shareReport(context: Context, report: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
    try {
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: RuntimeException) {
        // no app to share with
    }
}
