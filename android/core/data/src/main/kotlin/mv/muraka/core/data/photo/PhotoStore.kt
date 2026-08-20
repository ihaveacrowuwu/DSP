package mv.muraka.core.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import mv.muraka.core.common.DispatcherProvider
import mv.muraka.core.model.CaptureLimits
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where photograph bytes live between capture and acknowledgement.
 *
 * Three rules from `sync-protocol.md` are implemented here, and each one exists because
 * skipping it loses a photograph:
 *
 * 1. **Copy at capture time.** A gallery `content://` URI can be revoked, and the file
 *    behind it deleted, long before the outbox drains. What is queued has to be bytes we
 *    own, in app-private storage.
 * 2. **Write to a temporary name, then rename atomically.** A half-written file that
 *    looks complete is indistinguishable from a real one at upload time — the upload
 *    succeeds and the researcher gets a truncated image.
 * 3. **Downscale before uploading.** The server analyses at 224 px per grid cell, so a
 *    5×5 grid gains nothing above roughly 1600 px. That is far under the 12 MiB cap and
 *    much kinder to a resort Wi-Fi connection than a 12-megapixel original.
 *
 * EXIF orientation is applied to the pixels rather than preserved as a tag, because the
 * server strips EXIF when it re-encodes — a photograph relying on an orientation tag
 * would reach the researcher sideways, and the patch lattice with it.
 */
@Singleton
class PhotoStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val directory: File
        get() = File(context.filesDir, "outbox-photos").apply { mkdirs() }

    fun fileFor(photoId: String): File = File(directory, "$photoId.jpg")

    /**
     * Copies, rotates and downscales a picked or captured image into app-private storage.
     *
     * Returns the stored file, or null if the source could not be decoded — which is an
     * ordinary outcome for a corrupt file from an action camera, not an error worth
     * crashing over.
     */
    suspend fun store(photoId: String, source: Uri): File? = withContext(dispatchers.io) {
        val bitmap = decodeDownscaled(source) ?: return@withContext null
        val oriented = applyExifOrientation(bitmap, source)
        writeAtomically(photoId, oriented)
    }

    /** Same, for bytes already in memory — the CameraX capture path. */
    suspend fun store(photoId: String, bytes: ByteArray): File? = withContext(dispatchers.io) {
        val bitmap = decodeDownscaled(bytes) ?: return@withContext null
        writeAtomically(photoId, bitmap)
    }

    /**
     * Re-encodes an already-stored photograph smaller still, for a `413` the server
     * refused. The caller uploads the result under a **new** photo id, because the old one
     * may already be half-known to the server.
     */
    suspend fun downscaleFurther(sourceId: String, targetId: String): File? =
        withContext(dispatchers.io) {
            val source = fileFor(sourceId)
            if (!source.exists()) return@withContext null
            val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: return@withContext null
            val smaller = scaleToLongestEdge(bitmap, CaptureLimits.UPLOAD_MAX_EDGE_PX / 2)
            writeAtomically(targetId, smaller, quality = RETRY_JPEG_QUALITY)
        }

    /**
     * Deletes a photograph's bytes.
     *
     * Called **only** once the server's own `photos[]` lists the id — not when an upload
     * call returns, and not when a local flag is set. Deleting earlier is how a photograph
     * disappears with nothing left to retry from.
     */
    suspend fun delete(photoId: String) = withContext(dispatchers.io) {
        fileFor(photoId).delete()
        Unit
    }

    /** Everything for an account, after `DELETE /v1/me` succeeds. */
    suspend fun deleteAll() = withContext(dispatchers.io) {
        directory.listFiles()?.forEach { it.delete() }
        Unit
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun writeAtomically(photoId: String, bitmap: Bitmap, quality: Int = CaptureLimits.UPLOAD_JPEG_QUALITY): File? {
        val target = fileFor(photoId)
        val temporary = File(directory, "$photoId.jpg.part")
        return runCatching {
            FileOutputStream(temporary).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                // Force the bytes out before the rename, or the rename can be visible
                // while the contents are not.
                out.flush()
                out.fd.sync()
            }
            // The rename is the commit point: until it happens there is no file under the
            // real name at all, so nothing can read a partial one.
            if (!temporary.renameTo(target)) error("could not rename ${temporary.name}")
            target
        }.getOrElse {
            temporary.delete()
            null
        }
    }

    private fun decodeDownscaled(source: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        decoded?.let { scaleToLongestEdge(it, CaptureLimits.UPLOAD_MAX_EDGE_PX) }
    }.getOrNull()

    private fun decodeDownscaled(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?.let { scaleToLongestEdge(it, CaptureLimits.UPLOAD_MAX_EDGE_PX) }
    }.getOrNull()

    /**
     * Powers of two only, and always erring towards *too large*.
     *
     * `inSampleSize` is a decode-time shortcut that avoids allocating a 12-megapixel
     * bitmap on a mid-range phone; the exact size comes from [scaleToLongestEdge]
     * afterwards. Sampling past the target would throw away detail the model needs.
     */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= CaptureLimits.UPLOAD_MAX_EDGE_PX) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleToLongestEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Rotates the pixels to match the EXIF orientation tag.
     *
     * The server re-encodes and strips EXIF, so an image that relies on the tag arrives
     * sideways — and the patch lattice is drawn over the centre square, which would then
     * be the wrong square.
     */
    private fun applyExifOrientation(bitmap: Bitmap, source: Uri): Bitmap = runCatching {
        val degrees = context.contentResolver.openInputStream(source)?.use { stream ->
            when (
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        if (degrees == 0f) {
            bitmap
        } else {
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                Matrix().apply { postRotate(degrees) },
                true,
            )
        }
    }.getOrDefault(bitmap)

    private companion object {
        /** Lower than the first attempt: this path exists because the server said 413. */
        const val RETRY_JPEG_QUALITY = 70
    }
}
