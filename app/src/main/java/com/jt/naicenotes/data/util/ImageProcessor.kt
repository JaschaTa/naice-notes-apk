package com.jt.naicenotes.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

object ImageProcessor {

    suspend fun loadAndDownscale(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1024,
        jpegQuality: Int = 85,
    ): ByteArray = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val bitmap: Bitmap = try {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val maxSide = maxOf(info.size.width, info.size.height)
                if (maxSide > maxDimension) {
                    val sample = (maxSide.toFloat() / maxDimension).toInt().coerceAtLeast(1)
                    decoder.setTargetSampleSize(sample)
                }
            }
        } catch (e: Exception) {
            throw IOException("Could not load image ($uri): ${e.message}", e)
        }

        // ImageDecoder's targetSampleSize gets us close; one more scale pass for exactness.
        val scale = minOf(
            1f,
            maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height),
        )
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true,
            )
        } else {
            bitmap
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        out.toByteArray()
    }
}
