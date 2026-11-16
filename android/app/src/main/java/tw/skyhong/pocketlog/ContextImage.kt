package tw.skyhong.pocketlog

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

object ContextImage {
    /** Bounds the software copy to 34 MB; scales to 720 px and encodes at most 300 KB of JPEG. */
    fun jpeg(result: AccessibilityService.ScreenshotResult): String? {
        val buffer = result.hardwareBuffer
        if (buffer.width !in 1..4096 || buffer.height !in 1..4096 || buffer.width.toLong() * buffer.height > 8_500_000) return null
        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
        try {
            // Software copy first: software Canvas cannot draw a hardware bitmap on all devices.
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            try {
                if (mostlyBlack(software)) return null
                val ratio = minOf(1.0, RecordingRules.MAX_IMAGE_EDGE.toDouble() / maxOf(software.width, software.height))
                val scaled = Bitmap.createScaledBitmap(software, (software.width * ratio).toInt().coerceAtLeast(1), (software.height * ratio).toInt().coerceAtLeast(1), true)
                try {
                    for (quality in listOf(80, 65, 50, 35, 25)) {
                        val stream = ByteArrayOutputStream()
                        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)) return null
                        val bytes = stream.toByteArray()
                        try { if (bytes.size <= RecordingRules.MAX_IMAGE_BYTES) return Base64.encodeToString(bytes, Base64.NO_WRAP) }
                        finally { bytes.fill(0) }
                    }
                } finally { if (scaled !== software) scaled.recycle() }
            } finally { software.recycle() }
        } finally { hardware.recycle() }
        return null
    }

    /** Secure or blank compositor output is mostly black; such frames carry no context. */
    private fun mostlyBlack(bitmap: Bitmap): Boolean {
        val sample = Bitmap.createScaledBitmap(bitmap, 48, 48, false)
        try {
            val pixels = IntArray(48 * 48)
            sample.getPixels(pixels, 0, 48, 0, 0, 48, 48)
            return isMostlyBlack(pixels)
        } finally { if (sample !== bitmap) sample.recycle() }
    }
    internal fun isMostlyBlack(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return true
        val dark = pixels.count { p -> ((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff) < 30 }
        return dark >= pixels.size * 98 / 100
    }
}
