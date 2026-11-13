package ru.inikonoff.zerno.adapter

import android.content.Context
import android.os.Build
import net.sourceforge.opencamera.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the stack trace of a crash in the app's private storage, so that it can be shown (and
 *  copied) the next time the app starts. Nothing is sent anywhere.
 */
object CrashLog {
    private const val FILE_NAME = "zerno_crash.txt"
    private const val MAX_LENGTH = 30_000
    private var installed = false

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun install(context: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val header = "Zerno ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                        "$time, thread ${thread.name}\n\n"
                file(context).writeText((header + trace).take(MAX_LENGTH))
            } catch (e: Throwable) {
                // nothing more can be done here
            }
            // let the system handle the crash as usual
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? = try {
        file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
            // ignore
        }
    }
}
