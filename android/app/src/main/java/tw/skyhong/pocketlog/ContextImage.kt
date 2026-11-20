package tw.skyhong.pocketlog

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object ContextImage {
    /** Bounded software copy (at most 34 MB) so the hardware buffer can be closed right away. */
    fun copy(result: AccessibilityService.ScreenshotResult): Bitmap? {
        val buffer = result.hardwareBuffer
        if (!fitsDimensions(buffer.width, buffer.height)) return null
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

    fun fitsDimensions(width: Int, height: Int): Boolean =
        width in 1..RecordingRules.MAX_IMAGE_EDGE && height in 1..RecordingRules.MAX_IMAGE_EDGE &&
            width.toLong() * height <= RecordingRules.MAX_IMAGE_PIXELS

    /** Try lossless WebP at native resolution, then favor readable JPEG over reducing dimensions. */
    fun encode(bitmap: Bitmap): JSONObject? {
        encodeWithinLimit(bitmap, Bitmap.CompressFormat.WEBP_LOSSLESS, 50)?.let {
            return JSONObject().put("format", "webp").put("base64", it)
        }
        val longest = maxOf(bitmap.width, bitmap.height)
        for (edge in listOf(longest, 1600, 1080, 800).distinct()) {
            if (edge > longest) continue
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * edge / longest).coerceAtLeast(1),
                (bitmap.height * edge / longest).coerceAtLeast(1),
                true,
            )
            try {
                for (quality in listOf(88, 74)) {
                    encodeWithinLimit(scaled, Bitmap.CompressFormat.JPEG, quality)?.let {
                        return JSONObject().put("format", "jpeg").put("base64", it)
                    }
                }
            } finally { if (scaled !== bitmap) scaled.recycle() }
        }
        return null
    }

    private fun encodeWithinLimit(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): String? {
        val stream = ByteArrayOutputStream()
        if (!bitmap.compress(format, quality, stream) || stream.size() > RecordingRules.MAX_IMAGE_BYTES) return null
        val bytes = stream.toByteArray()
        return try { Base64.encodeToString(bytes, Base64.NO_WRAP) } finally { bytes.fill(0) }
    }
}
