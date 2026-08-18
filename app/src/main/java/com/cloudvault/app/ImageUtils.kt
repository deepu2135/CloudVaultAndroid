package com.cloudvault.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log

object ImageUtils {
    private const val TAG = "ImageUtils"

    fun decodeOrientedBitmap(filePath: String, maxDimension: Int = 4096): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(filePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            var inSampleSize = 1
            while (options.outWidth / inSampleSize > maxDimension || options.outHeight / inSampleSize > maxDimension) {
                inSampleSize *= 2
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            val rawBitmap = BitmapFactory.decodeFile(filePath, options) ?: return null

            val exif = ExifInterface(filePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
                else -> return rawBitmap
            }

            val rotatedBitmap = Bitmap.createBitmap(
                rawBitmap,
                0,
                0,
                rawBitmap.width,
                rawBitmap.height,
                matrix,
                true
            )
            if (rotatedBitmap != rawBitmap) {
                rawBitmap.recycle()
            }
            rotatedBitmap
        } catch (e: Throwable) {
            Log.e(TAG, "Error decoding oriented bitmap for $filePath", e)
            try {
                BitmapFactory.decodeFile(filePath)
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun preparePhotoForTelegramUpload(context: android.content.Context, localPath: String, maxDimension: Int = 2560): Pair<java.io.File, Boolean> {
        val originalFile = java.io.File(localPath)
        if (!originalFile.exists() || originalFile.length() <= 0L) {
            return Pair(originalFile, false)
        }

        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(localPath, options)
            val width = options.outWidth
            val height = options.outHeight

            // If dimensions are within Telegram's native photo limits and size is already reasonable, use original directly
            if (width > 0 && height > 0 && width <= maxDimension && height <= maxDimension && (width + height) <= 6000 && originalFile.length() <= 4_000_000L) {
                return Pair(originalFile, false)
            }

            // Downsample and compress to fast, high-quality JPEG (e.g. max 2560px, 88% quality)
            val bitmap = decodeOrientedBitmap(localPath, maxDimension = maxDimension)
            if (bitmap != null) {
                val compressDir = java.io.File(context.cacheDir, "autobackup_compressed").apply { if (!exists()) mkdirs() }
                val tempFile = java.io.File(compressDir, "opt_${System.currentTimeMillis()}_${originalFile.name}")
                java.io.FileOutputStream(tempFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
                bitmap.recycle()
                if (tempFile.exists() && tempFile.length() > 0L) {
                    return Pair(tempFile, true)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "preparePhotoForTelegramUpload fallback for $localPath", e)
        }
        return Pair(originalFile, false)
    }
}
