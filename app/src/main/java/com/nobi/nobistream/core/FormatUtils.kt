package com.nobi.nobistream.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small pure formatting helpers shared by list rows and the player. */
object FormatUtils {

    fun formatDuration(totalSeconds: Int): String {
        if (totalSeconds <= 0) return "0:00"
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            String.format(Locale.US, "%d %s", bytes, units[unitIndex])
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    fun formatDate(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return ""
        val format = SimpleDateFormat("d MMM yyyy", Locale.US)
        return format.format(Date(epochSeconds * 1000L))
    }

    fun formatResumePosition(positionSeconds: Long, durationSeconds: Int): String {
        return formatDuration(positionSeconds.toInt()) + " / " + formatDuration(durationSeconds)
    }

    /**
     * v9 headline hygiene: strip LEADING emoji / symbols / punctuation
     * from a video title for DISPLAY only (hero + card titles). The
     * caption itself is untouched in the player details. A title that
     * starts with a letter or digit is returned unchanged; a title
     * that is only emoji falls back to the original text.
     */
    fun stripLeadingEmoji(text: String): String {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            // Character.isLetterOrDigit covers every letter/digit
            // category; emoji, symbols, punctuation, spaces, format
            // characters (ZWJ, variation selectors) are all skipped.
            if (Character.isLetterOrDigit(cp)) break
            i += Character.charCount(cp)
        }
        val stripped = text.substring(i).trimStart()
        return stripped.ifEmpty { text }
    }
}
