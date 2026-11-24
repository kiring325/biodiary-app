package jp.morinote.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun PhotoCropDialog(
    sourceUri: Uri,
    onDismiss: () -> Unit,
    onUseOriginal: () -> Unit,
    onCropped: (Uri) -> Unit,
    circularFrame: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(sourceUri) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(sourceUri) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var cropFailed by remember { mutableStateOf(false) }
    var cropView by remember(sourceUri) { mutableStateOf<CropImageView?>(null) }

    LaunchedEffect(sourceUri) {
        loading = true
        bitmap = withContext(Dispatchers.IO) { decodeOrientedBitmap(context, sourceUri) }
        loading = false
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (circularFrame) "アイコンをトリミング" else "写真をトリミング") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("画像をドラッグして位置を調整できます。2本指で拡大・縮小します。")
                val loadedBitmap = bitmap
                if (loadedBitmap == null) {
                    Text(if (loading) "写真を読み込んでいます…" else "この写真を読み込めませんでした。")
                } else {
                    val view = remember(loadedBitmap, circularFrame) { CropImageView(context, loadedBitmap, circularFrame) }
                    AndroidView(
                        factory = { view },
                        update = { cropView = it },
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                }
                if (cropFailed) Text(if (circularFrame) "切り抜きに失敗しました。もう一度お試しください。" else "切り抜きに失敗しました。そのまま使うこともできます。", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!circularFrame) TextButton(onClick = onUseOriginal, enabled = !saving) { Text("そのまま使う") }
                Button(
                    onClick = {
                        val source = bitmap ?: return@Button
                        val bounds = cropView?.cropBounds() ?: return@Button
                        saving = true
                        cropFailed = false
                        scope.launch {
                            val croppedUri = withContext(Dispatchers.IO) { writeCrop(context, sourceUri, source, bounds) }
                            saving = false
                            if (croppedUri == null) cropFailed = true else onCropped(croppedUri)
                        }
                    },
                    enabled = bitmap != null && !saving,
                ) { Text(if (saving) "作成中…" else if (circularFrame) "この範囲で保存" else "切り抜いて使う") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("キャンセル") } },
    )
}

private class CropImageView(context: Context, private val bitmap: Bitmap, private val circularFrame: Boolean) : View(context) {
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f.dpToPx(context)
    }
    private var baseScale = 1f
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = scale
            scale = (scale * detector.scaleFactor).coerceIn(baseScale, baseScale * 4f)
            val ratio = scale / oldScale
            offsetX = detector.focusX - width / 2f - (detector.focusX - width / 2f - offsetX) * ratio
            offsetY = detector.focusY - height / 2f - (detector.focusY - height / 2f - offsetY) * ratio
            clampOffsets()
            invalidate()
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val side = min(w, h).toFloat()
        if (side <= 0f) return
        baseScale = max(side / bitmap.width, side / bitmap.height)
        scale = baseScale
        offsetX = 0f
        offsetY = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(35, 40, 36))
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        canvas.save()
        if (circularFrame) {
            canvas.clipPath(Path().apply { addCircle(width / 2f, height / 2f, side / 2f, Path.Direction.CW) })
        } else canvas.clipRect(left, top, left + side, top + side)
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val imageRect = RectF(
            width / 2f - scaledWidth / 2f + offsetX,
            height / 2f - scaledHeight / 2f + offsetY,
            width / 2f + scaledWidth / 2f + offsetX,
            height / 2f + scaledHeight / 2f + offsetY,
        )
        canvas.drawBitmap(bitmap, null, imageRect, imagePaint)
        canvas.restore()
        if (circularFrame) canvas.drawCircle(width / 2f, height / 2f, side / 2f - 1f, framePaint)
        else canvas.drawRect(left + 1f, top + 1f, left + side - 1f, top + side - 1f, framePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                offsetX += event.x - lastX
                offsetY += event.y - lastY
                lastX = event.x
                lastY = event.y
                clampOffsets()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun clampOffsets() {
        val side = min(width, height).toFloat()
        val overflowX = max(0f, bitmap.width * scale - side)
        val overflowY = max(0f, bitmap.height * scale - side)
        offsetX = offsetX.coerceIn(-overflowX / 2f, overflowX / 2f)
        offsetY = offsetY.coerceIn(-overflowY / 2f, overflowY / 2f)
    }

    fun cropBounds(): Rect {
        val side = min(width, height).toFloat()
        val cropLeft = (width - side) / 2f
        val cropTop = (height - side) / 2f
        val imageLeft = width / 2f - bitmap.width * scale / 2f + offsetX
        val imageTop = height / 2f - bitmap.height * scale / 2f + offsetY
        val cropSide = (side / scale).roundToInt().coerceAtLeast(1).coerceAtMost(min(bitmap.width, bitmap.height))
        val left = ((cropLeft - imageLeft) / scale).roundToInt().coerceIn(0, bitmap.width - cropSide)
        val top = ((cropTop - imageTop) / scale).roundToInt().coerceIn(0, bitmap.height - cropSide)
        return Rect(left, top, left + cropSide, top + cropSide)
    }
}

private fun Float.dpToPx(context: Context): Float = this * context.resources.displayMetrics.density

internal fun decodeOrientedBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / sampleSize > 4096) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("写真を読み込めませんでした")
    val orientation = context.contentResolver.openInputStream(uri).use { stream ->
        ExifInterface(requireNotNull(stream)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
    }
    if (matrix.isIdentity()) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { decoded.recycle() }
}.getOrNull()

private fun writeCrop(context: Context, sourceUri: Uri, bitmap: Bitmap, bounds: Rect): Uri? = runCatching {
    val outputDir = File(context.cacheDir, "photo-crops").apply { mkdirs() }
    val outputFile = File(outputDir, "crop-${System.currentTimeMillis()}.jpg")
    val cropped = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
    outputFile.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    cropped.recycle()

    val sourceExif = runCatching {
        context.contentResolver.openInputStream(sourceUri).use { ExifInterface(requireNotNull(it)) }
    }.getOrNull()
    if (sourceExif != null) {
        val outputExif = ExifInterface(outputFile.absolutePath)
        listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP,
        ).forEach { tag -> sourceExif.getAttribute(tag)?.let { outputExif.setAttribute(tag, it) } }
        outputExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        outputExif.saveAttributes()
    }
    Uri.fromFile(outputFile)
}.getOrNull()
