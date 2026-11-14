package tw.skyhong.pocketlog

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

object ContextImage {
    /** Bounds the software copy to 34 MB; scales to 480 px before bounded JPEG encoding. */
    fun jpeg(result: AccessibilityService.ScreenshotResult): String? {
        val buffer = result.hardwareBuffer
        if (buffer.width !in 1..4096 || buffer.height !in 1..4096 || buffer.width.toLong() * buffer.height > 8_500_000) return null
        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
        try {
            // Software copy first: software Canvas cannot draw a hardware bitmap on all devices.
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            try {
                val ratio = minOf(1.0, 480.0 / maxOf(software.width, software.height))
                val scaled = Bitmap.createScaledBitmap(software, (software.width * ratio).toInt().coerceAtLeast(1), (software.height * ratio).toInt().coerceAtLeast(1), true)
                try {
                    for (quality in listOf(60, 40, 25)) {
                        val stream = ByteArrayOutputStream()
                        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)) return null
                        val bytes = stream.toByteArray()
                        try { if (bytes.size <= 24 * 1024) return Base64.encodeToString(bytes, Base64.NO_WRAP) }
                        finally { bytes.fill(0) }
                    }
                } finally { if (scaled !== software) scaled.recycle() }
            } finally { software.recycle() }
        } finally { hardware.recycle() }
        return null
    }
}
