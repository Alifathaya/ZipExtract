package com.zipextract.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BitmapEditor {

    fun crop(source: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val l = (left.coerceIn(0f, 1f) * source.width).toInt().coerceIn(0, source.width - 1)
        val t = (top.coerceIn(0f, 1f) * source.height).toInt().coerceIn(0, source.height - 1)
        val r = (right.coerceIn(0f, 1f) * source.width).toInt().coerceIn(l + 1, source.width)
        val b = (bottom.coerceIn(0f, 1f) * source.height).toInt().coerceIn(t + 1, source.height)
        return Bitmap.createBitmap(source, l, t, r - l, b - t)
    }

    fun drawStrokes(
        source: Bitmap,
        strokes: List<StrokeData>,
    ): Bitmap {
        if (strokes.isEmpty()) return source
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        strokes.forEach { stroke ->
            if (stroke.points.size < 2) return@forEach
            paint.color = stroke.colorArgb
            paint.strokeWidth = stroke.widthPx * (source.width / stroke.canvasWidth.coerceAtLeast(1f))
            val path = android.graphics.Path()
            val first = stroke.points.first()
            path.moveTo(first.x * source.width, first.y * source.height)
            for (i in 1 until stroke.points.size) {
                val p = stroke.points[i]
                path.lineTo(p.x * source.width, p.y * source.height)
            }
            canvas.drawPath(path, paint)
        }
        return output
    }

    fun saveToPictures(context: Context, bitmap: Bitmap, baseName: String): File? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeBase = baseName.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "edited" }
        val fileName = "${safeBase}_edit_$stamp.jpg"

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FileNest")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Gagal membuat MediaStore entry")
                resolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                        error("Gagal compress bitmap")
                    }
                } ?: error("Gagal membuka output stream")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                // Also write a shareable cache copy for FileProvider.
                val cache = File(context.cacheDir, fileName)
                FileOutputStream(cache).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                cache
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "FileNest",
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                file
            }
        }.getOrElse {
            runCatching {
                val cache = File(context.cacheDir, fileName)
                FileOutputStream(cache).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                cache
            }.getOrNull()
        }
    }

    fun scaleForEditing(source: Bitmap, maxSide: Int = 2048): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSide) return source
        val scale = maxSide.toFloat() / longest
        val matrix = Matrix().apply { setScale(scale, scale) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}

data class StrokePoint(val x: Float, val y: Float)

data class StrokeData(
    val points: List<StrokePoint>,
    val colorArgb: Int,
    val widthPx: Float,
    val canvasWidth: Float,
)
