package tw.skyhong.pocketlog

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

object ContextImage {
    /** Bounded software copy (at most 34 MB) so the hardware buffer can be closed right away. */
    fun copy(result: AccessibilityService.ScreenshotResult): Bitmap? {
        val buffer = result.hardwareBuffer
        if (buffer.width !in 1..4096 || buffer.height !in 1..4096 || buffer.width.toLong() * buffer.height > 8_500_000) return null
        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
        // Software copy first: software Canvas cannot draw a hardware bitmap on all devices.
        return try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
    }

    /** Small visual hash for skipping unchanged screens, or null for a black/blank frame that must not be kept. */
    fun fingerprint(bitmap: Bitmap): Long? {
        val sample = Bitmap.createScaledBitmap(bitmap, 48, 48, false)
        try {
            val pixels = IntArray(48 * 48)
            sample.getPixels(pixels, 0, 48, 0, 0, 48, 48)
            if (ScreenChange.isMostlyBlack(pixels)) return null
        } finally { if (sample !== bitmap) sample.recycle() }
        val grid = Bitmap.createScaledBitmap(bitmap, ScreenChange.HASH_WIDTH, ScreenChange.HASH_HEIGHT, true)
        try {
            val pixels = IntArray(ScreenChange.HASH_WIDTH * ScreenChange.HASH_HEIGHT)
            grid.getPixels(pixels, 0, ScreenChange.HASH_WIDTH, 0, 0, ScreenChange.HASH_WIDTH, ScreenChange.HASH_HEIGHT)
            return ScreenChange.dHash(IntArray(pixels.size) { ScreenChange.luma(pixels[it]) })
        } finally { if (grid !== bitmap) grid.recycle() }
    }

    /** Scales to 720 px and encodes at most 300 KB of JPEG. */
    fun jpeg(bitmap: Bitmap): String? {
        val ratio = minOf(1.0, RecordingRules.MAX_IMAGE_EDGE.toDouble() / maxOf(bitmap.width, bitmap.height))
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        try {
            for (quality in listOf(80, 65, 50, 35, 25)) {
                val stream = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)) return null
                val bytes = stream.toByteArray()
                try { if (bytes.size <= RecordingRules.MAX_IMAGE_BYTES) return Base64.encodeToString(bytes, Base64.NO_WRAP) }
                finally { bytes.fill(0) }
            }
        } finally { if (scaled !== bitmap) scaled.recycle() }
        return null
    }
}
