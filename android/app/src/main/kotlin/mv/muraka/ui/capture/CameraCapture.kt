package mv.muraka.ui.capture

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.nio.ByteBuffer

/**
 * In-app capture with CameraX.
 *
 * The system camera intent would be less code, but it costs two extra taps - the system
 * camera's own confirm step, then the return - and NFR6 caps the whole flow at eight. An
 * in-app shutter is one tap per photograph.
 *
 * Bytes come back in memory rather than as a file, because [PhotoStore] is going to
 * downscale and re-encode them anyway; writing a full-resolution intermediate would be a
 * 12-megapixel file created only to be thrown away.
 */
@Composable
fun CameraCapture(
    onCaptured: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    remaining: Int,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close the camera", tint = Color.White)
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "$remaining more photograph${if (remaining == 1) "" else "s"}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
            LargeFloatingActionButton(
                onClick = { imageCapture.takeInto(context, onCaptured) },
                modifier = Modifier.size(76.dp),
            ) {
                Icon(Icons.Filled.Lens, contentDescription = "Take a photograph")
            }
        }
    }
}

/** Takes one photograph and hands back its JPEG bytes. */
private fun ImageCapture.takeInto(context: Context, onCaptured: (ByteArray) -> Unit) {
    takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                image.use { onCaptured(it.toJpegBytes()) }
            }

            override fun onError(exception: ImageCaptureException) {
                // Deliberately silent: a failed shutter press is not worth an error
                // dialogue mid-dive. The contributor presses it again, and nothing has
                // been lost because nothing was queued. A failure that MATTERS - the
                // photograph not reaching disk - is reported by PhotoStore instead.
                Unit
            }
        },
    )
}

/** CameraX already hands back JPEG for `takePicture`; this copies the plane out. */
private fun ImageProxy.toJpegBytes(): ByteArray {
    val buffer: ByteBuffer = planes[0].buffer
    return ByteArray(buffer.remaining()).also(buffer::get)
}
