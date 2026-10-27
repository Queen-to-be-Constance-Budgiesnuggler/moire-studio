package dk.cbavf.studio.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import dk.cbavf.studio.camera.model.Stack
import dk.cbavf.studio.camera.model.StackMode

/**
 * Decodes a JPEG and turns it upright for a phone held in portrait. Front cameras are mirrored
 * back, so the picture shows the scene as it is, not as a mirror.
 */
fun decodeUpright(jpeg: ByteArray, sensorOrientation: Int, front: Boolean, sample: Int): Bitmap {
    val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Could not decode capture")
    if (sensorOrientation == 0 && !front) return raw
    val m = Matrix().apply {
        postRotate(sensorOrientation.toFloat())
        if (front) postScale(-1f, 1f)
    }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, false).also { if (it !== raw) raw.recycle() }
}

/** Combines upright frames of equal size with [mode], one row at a time. Recycles nothing. */
fun stack(frames: List<Bitmap>, mode: StackMode): Bitmap {
    val w = frames.first().width
    val h = frames.first().height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val row = IntArray(w)
    if (mode is StackMode.Interlace) {
        for (y in 0 until h) {
            frames[Stack.interlaceFrame(y, mode, frames.size)].getPixels(row, 0, w, 0, y, w, 1)
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }
    val rows = List(frames.size) { IntArray(w) }
    for (y in 0 until h) {
        frames.forEachIndexed { i, f -> f.getPixels(rows[i], 0, w, 0, y, w, 1) }
        Stack.combineRow(rows, mode, row)
        out.setPixels(row, 0, w, 0, y, w, 1)
    }
    return out
}
