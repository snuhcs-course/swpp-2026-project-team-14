package com.swpp.stylemate.data.wardrobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Only the capture directory is shared with the camera through temporary URI access. */
class CaptureFiles(private val context: Context) {
    private val directory get() = File(context.cacheDir, "wardrobe-captures").apply { mkdirs() }
    fun create(): String = File.createTempFile("capture-", ".jpg", directory).name
    fun write(name: String, bitmap: Bitmap) {
        file(name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
    }
    private fun file(name: String): File {
        require(name.matches(Regex("capture-[0-9]+\\.jpg")))
        return File(directory, name)
    }
    fun uri(name: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.captures", file(name))
    fun delete(name: String?) { if (name != null) file(name).delete() }
    fun exists(name: String): Boolean = file(name).let { it.isFile && it.length() > 0 }
    fun cleanExpired() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
    fun preview(name: String): Bitmap? {
        val path = file(name).absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
