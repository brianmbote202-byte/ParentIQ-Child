package com.parentalcontrol.childapp.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import java.io.ByteArrayOutputStream

object AppIconUtils {

    fun drawableToBase64(drawable: Drawable): String {

        val bitmap = drawableToBitmap(drawable)

        val stream = ByteArrayOutputStream()

        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)

        return Base64.encodeToString(
            stream.toByteArray(),
            Base64.NO_WRAP
        )
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {

        return when (drawable) {

            is BitmapDrawable -> drawable.bitmap

            is AdaptiveIconDrawable -> {
                val bitmap = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )

                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)

                bitmap
            }

            else -> {
                val bitmap = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )

                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)

                bitmap
            }
        }
    }
}