package com.hoons1994.iphoneduoanimation

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import kotlin.math.min

class SnapshotStore(private val context: Context) {

    enum class Kind(
        val key: String,
        val validationKey: String,
        val geometryRole: SnapshotGeometry.Role,
    ) {
        COVER(
            key = "cover_snapshot_uri",
            validationKey = "cover_snapshot_geometry_valid",
            geometryRole = SnapshotGeometry.Role.COVER,
        ),
        INNER(
            key = "inner_snapshot_uri",
            validationKey = "inner_snapshot_geometry_valid",
            geometryRole = SnapshotGeometry.Role.INNER,
        ),
    }

    private val prefs = context.getSharedPreferences("snapshot_store", Context.MODE_PRIVATE)

    fun persist(kind: Kind, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some pickers grant access without a persistable permission. The
            // URI is still useful for the current session, so keep it stored.
        }
        prefs.edit()
            .putString(kind.key, uri.toString())
            .remove(kind.validationKey)
            .apply()
    }

    fun storedUri(kind: Kind): Uri? = prefs.getString(kind.key, null)?.let(Uri::parse)

    fun hasSnapshot(kind: Kind): Boolean {
        val uri = storedUri(kind) ?: return false
        if (prefs.contains(kind.validationKey) && !prefs.getBoolean(kind.validationKey, true)) {
            return false
        }
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun load(kind: Kind): Bitmap? {
        val uri = storedUri(kind) ?: return null
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val longest = maxOf(width, height)
                if (longest > MAX_DIMENSION) {
                    val scale = MAX_DIMENSION.toFloat() / longest.toFloat()
                    decoder.setTargetSize(
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }

            val geometry = SnapshotGeometry.assess(
                kind.geometryRole,
                bitmap.width,
                bitmap.height,
            )
            val usable = geometry != SnapshotGeometry.Assessment.LOOKS_SWAPPED &&
                geometry != SnapshotGeometry.Assessment.INVALID
            prefs.edit().putBoolean(kind.validationKey, usable).apply()

            if (usable) bitmap else null
        } catch (_: Exception) {
            // Do not keep advertising an imported snapshot that the app can no
            // longer decode/read after a reboot, provider change, or lost grant.
            prefs.edit()
                .remove(kind.key)
                .remove(kind.validationKey)
                .apply()
            null
        }
    }

    fun loadOrFallback(kind: Kind): Bitmap = load(kind) ?: when (kind) {
        Kind.COVER -> createFallback(1080, 2520, "COVER SNAPSHOT")
        Kind.INNER -> createFallback(2208, 1840, "INNER SNAPSHOT")
    }

    private fun createFallback(width: Int, height: Int, label: String): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Color.rgb(38, 47, 76),
            Color.rgb(11, 14, 24),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val margin = width * 0.07f
        val top = height * 0.12f
        val columns = 4
        val gap = width * 0.035f
        val tile = (width - margin * 2f - gap * (columns - 1)) / columns
        val colors = intArrayOf(
            Color.rgb(241, 94, 94),
            Color.rgb(91, 157, 255),
            Color.rgb(94, 206, 143),
            Color.rgb(246, 181, 74),
            Color.rgb(164, 111, 255),
            Color.rgb(72, 196, 214),
            Color.rgb(238, 115, 184),
            Color.rgb(119, 132, 156),
        )

        repeat(4) { row ->
            repeat(columns) { column ->
                val left = margin + column * (tile + gap)
                val y = top + row * (tile + gap)
                paint.color = colors[(row * columns + column) % colors.size]
                canvas.drawRoundRect(
                    RectF(left, y, left + tile, y + tile),
                    tile * 0.22f,
                    tile * 0.22f,
                    paint,
                )
            }
        }

        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = min(width, height) * 0.055f
        canvas.drawText(label, width / 2f, height * 0.78f, paint)
        paint.alpha = 170
        paint.textSize = min(width, height) * 0.032f
        canvas.drawText("Import a real screenshot for the POC", width / 2f, height * 0.84f, paint)

        return bitmap
    }

    companion object {
        private const val MAX_DIMENSION = 2400
    }
}
