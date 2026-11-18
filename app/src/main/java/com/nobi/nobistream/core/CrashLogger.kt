package com.nobi.nobistream.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.nobi.nobistream.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v8.1 crash logger — Sam reported v8 "logs bhi ni de raha".
 *
 * The app had NO UncaughtExceptionHandler anywhere, so a crash left
 * nothing on the phone: no stack trace, no file, no evidence. This
 * handler is installed FIRST in [com.nobi.nobistream.NobiStreamApplication.onCreate],
 * before any other init, so even a crash during ServiceLocator init
 * leaves a trace.
 *
 * What it writes:
 * - Downloads/NobiStream-crash.txt (via MediaStore, appended) — the
 *   file Sam can see and send. Falls back to the app files dir
 *   (files/NobiStream-crash.txt) if the MediaStore write fails.
 * - Downloads/NobiStream-start.txt — a startup marker rewritten on
 *   every Application.onCreate. If the marker exists, the Downloads
 *   write channel works on this phone; if a crash leaves NO crash
 *   file but the marker IS there, the crash was native (TDLib) or
 *   the process was killed by the system, not a Java crash.
 *
 * Every write is wrapped in try/catch — the logger must NEVER crash
 * the app itself. The previous default handler is always chained so
 * the system still shows the crash dialog / kills the process as
 * usual; this only adds the file.
 */
object CrashLogger {

    private const val CRASH_FILE = "NobiStream-crash.txt"
    private const val START_MARKER_FILE = "NobiStream-start.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashReport(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Logger failure must never mask the real crash.
            }
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
                // Nothing left to do — the process is dying.
            }
        }
    }

    /** Startup marker in Downloads — proves the write channel works. */
    fun writeStartupMarker(context: Context) {
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val text = "NobiStream started $stamp " +
                "(v${BuildConfig.VERSION_NAME} / code ${BuildConfig.VERSION_CODE})\n" +
                "If this file exists, Downloads writes work on this phone.\n"
            writeToDownloads(context.applicationContext, START_MARKER_FILE, text, append = false)
            writeToFilesDir(context.applicationContext, START_MARKER_FILE, text, append = false)
        } catch (_: Throwable) {
            // Marker is best-effort; a failure here must not block startup.
        }
    }

    private fun writeCrashReport(context: Context, thread: Thread, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val report = buildString {
            appendLine("===== NobiStream crash $stamp =====")
            appendLine("version: ${BuildConfig.VERSION_NAME} / code ${BuildConfig.VERSION_CODE}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.SDK_INT}")
            appendLine("thread: ${thread.name} (id ${thread.id})")
            appendLine(sw.toString())
            appendLine()
        }
        try {
            writeToDownloads(context, CRASH_FILE, report, append = true)
        } catch (_: Throwable) {
            // MediaStore failed — the files-dir copy is the fallback.
        }
        try {
            writeToFilesDir(context, CRASH_FILE, report, append = true)
        } catch (_: Throwable) {
            // Last resort exhausted; nothing more can be done here.
        }
    }

    /** Find an existing Downloads file by display name, if any. */
    private fun findDownloadsUri(context: Context, name: String): Uri? {
        val resolver = context.contentResolver
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        return try {
            resolver.query(uri, projection, selection, arrayOf(name), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    Uri.withAppendedPath(uri, id.toString())
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeToDownloads(context: Context, name: String, text: String, append: Boolean) {
        val resolver = context.contentResolver
        val existing = findDownloadsUri(context, name)
        if (existing != null) {
            // "wa" = append, "w" = overwrite. If the mode fails, fall
            // through to inserting a fresh file below.
            try {
                resolver.openOutputStream(existing, if (append) "wa" else "w")?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                return
            } catch (_: Throwable) {
                // Fall through to insert.
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
        }
        val newUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            resolver.openOutputStream(newUri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (_: Throwable) {
            // Inserted but unwritable — the files-dir copy covers it.
        }
    }

    private fun writeToFilesDir(context: Context, name: String, text: String, append: Boolean) {
        val file = File(context.filesDir, name)
        FileOutputStream(file, append).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }
}
