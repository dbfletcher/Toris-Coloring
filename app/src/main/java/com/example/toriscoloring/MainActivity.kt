package com.example.toriscoloring // <--- MAKE SURE THIS MATCHES YOUR PROJECT!

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF1F8E9)) {
                ColoringApp()
            }
        }
    }
}

data class CanvasState(val bitmap: ImageBitmap, val uri: Uri?, val resId: Int?)

// --- Image Loading & Manipulation Logic ---
fun getRotationFromExif(context: Context, uri: Uri): Int {
    var rotation = 0
    try {
        // UPGRADED: Using FileDescriptor to bypass Android streaming bugs
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return rotation
}

fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2; val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

// UPGRADED: Forces PNGs onto a solid white background and uses bulletproof FileDescriptors
fun loadMutableBitmapFromUri(context: Context, uri: Uri): ImageBitmap? {
    return try {
        val rotation = getRotationFromExif(context, uri)

        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val fd = pfd.fileDescriptor
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(fd, null, boundsOptions)

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOptions, 1024, 1024)
                inMutable = true
            }

            val bitmap = BitmapFactory.decodeFileDescriptor(fd, null, decodeOptions)

            if (bitmap != null) {
                val rotated = rotateBitmap(bitmap, rotation)
                // Flatten any transparency onto a white background
                val resultBmp = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(resultBmp)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(rotated, 0f, 0f, null)
                resultBmp.asImageBitmap()
            } else null
        }
    } catch (e: Exception) { e.printStackTrace(); null }
}

fun loadMutableBitmapFromRes(context: Context, resId: Int): ImageBitmap? {
    return try {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resId, boundsOptions)

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(boundsOptions, 1024, 1024)
            inMutable = true
        }

        val bitmap = BitmapFactory.decodeResource(context.resources, resId, decodeOptions)
        if (bitmap != null) {
            val resultBmp = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(resultBmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            resultBmp.asImageBitmap()
        } else null
    } catch (e: Exception) { null }
}

// --- Image Saving Logic ---
fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean {
    return try {
        val filename = "ToriColoring_${System.currentTimeMillis()}.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ToriColoring")
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            val stream: OutputStream? = context.contentResolver.openOutputStream(uri)
            stream?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } else false
    } catch (e: Exception) { e.printStackTrace(); false }
}

// --- The Core Flood Fill Algorithm ---
fun floodFill(bitmap: Bitmap, startX: Int, startY: Int, fillColor: Int, tolerance: Int = 50): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val targetColor = pixels[startY * width + startX]
    if (colorMatch(targetColor, fillColor, 0)) return bitmap

    val queue = java.util.LinkedList<Int>()
    val visited = BooleanArray(width * height)

    val startIndex = startY * width + startX
    queue.add(startIndex)
    visited[startIndex] = true

    while (queue.isNotEmpty()) {
        val index = queue.poll()!!
        val cx = index % width
        val cy = index / width

        if (colorMatch(pixels[index], targetColor, tolerance)) {
            pixels[index] = fillColor

            if (cy > 0 && !visited[index - width]) { queue.add(index - width); visited[index - width] = true }
            if (cy < height - 1 && !visited[index + width]) { queue.add(index + width); visited[index + width] = true }
            if (cx > 0 && !visited[index - 1]) { queue.add(index - 1); visited[index - 1] = true }
            if (cx < width - 1 && !visited[index + 1]) { queue.add(index + 1); visited[index + 1] = true }
        }
    }

    val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    newBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return newBitmap
}

fun colorMatch(color1: Int, color2: Int, tolerance: Int): Boolean {
    val rDiff = abs(android.graphics.Color.red(color1) - android.graphics.Color.red(color2))
    val gDiff = abs(android.graphics.Color.green(color1) - android.graphics.Color.green(color2))
    val bDiff = abs(android.graphics.Color.blue(color1) - android.graphics.Color.blue(color2))
    return rDiff <= tolerance && gDiff <= tolerance && bDiff <= tolerance
}

@Composable
fun ColoringApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ToriColoringPrefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    val defaultImageLibrary = listOf<Int>(
        R.drawable.ppcolor1, R.drawable.ppcolor2, R.drawable.ppcolor3,
        R.drawable.ppcolor4, R.drawable.ppcolor5, R.drawable.ppcolor6,
        R.drawable.ppcolor7, R.drawable.ppcolor8, R.drawable.ppcolor9
        // (You can add the Octonauts R.drawables here if you end up putting them in res/drawable!)
    )

    var customFolderUri by remember { mutableStateOf<Uri?>(prefs.getString("CustomColorFolder", null)?.let { Uri.parse(it) }) }
    var customImageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var customThumbnails by remember { mutableStateOf<Map<Uri, ImageBitmap>>(emptyMap()) }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedResId by remember { mutableStateOf<Int?>(defaultImageLibrary[0]) }

    var currentBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    var skipNextLoad by remember { mutableStateOf(false) }
    var isSettingsUnlocked by remember { mutableStateOf(false) }

    val historyStack = remember { mutableStateListOf<CanvasState>() }
    val redoStack = remember { mutableStateListOf<CanvasState>() }

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 8f)
        pan += offsetChange
    }

    val palette = listOf(
        Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFFEB3B),
        Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA),
        Color(0xFFF48FB1), Color(0xFF795548), Color(0xFFE0E0E0),
        Color(0xFF555555)
    )
    var selectedColor by remember { mutableStateOf(palette[0]) }

    // --- UPGRADED Incremental Data Loading ---
    LaunchedEffect(customFolderUri) {
        customFolderUri?.let { uri ->
            withContext(Dispatchers.IO) {
                val documentFile = DocumentFile.fromTreeUri(context, uri)
                val files = documentFile?.listFiles()
                    ?.filter { it.type?.startsWith("image/") == true }
                    ?.map { it.uri } ?: emptyList()

                withContext(Dispatchers.Main) {
                    customImageUris = files
                    customThumbnails = emptyMap() // Clear old thumbs
                }

                files.forEach { fileUri ->
                    try {
                        val rotation = getRotationFromExif(context, fileUri)

                        // Use safe FileDescriptor loading for thumbnails too!
                        context.contentResolver.openFileDescriptor(fileUri, "r")?.use { pfd ->
                            val fd = pfd.fileDescriptor
                            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFileDescriptor(fd, null, boundsOptions)

                            val decodeOptions = BitmapFactory.Options().apply {
                                inSampleSize = calculateInSampleSize(boundsOptions, 150, 150)
                            }

                            val bmp = BitmapFactory.decodeFileDescriptor(fd, null, decodeOptions)
                            if (bmp != null) {
                                val rotated = rotateBitmap(bmp, rotation)
                                // Flatten transparency for custom PNG thumbnails
                                val resultBmp = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(resultBmp)
                                canvas.drawColor(android.graphics.Color.WHITE)
                                canvas.drawBitmap(rotated, 0f, 0f, null)

                                val finalThumb = resultBmp.asImageBitmap()

                                // Update the UI incrementally as each image finishes!
                                withContext(Dispatchers.Main) {
                                    customThumbnails = customThumbnails + (fileUri to finalThumb)
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }
    }

    LaunchedEffect(selectedUri, selectedResId) {
        if (skipNextLoad) {
            skipNextLoad = false
            return@LaunchedEffect
        }

        isProcessing = true
        scale = 1f
        pan = Offset.Zero

        withContext(Dispatchers.IO) {
            if (selectedUri != null) {
                currentBitmap = loadMutableBitmapFromUri(context, selectedUri!!)
            } else if (selectedResId != null) {
                currentBitmap = loadMutableBitmapFromRes(context, selectedResId!!)
            }
        }
        isProcessing = false
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            prefs.edit().putString("CustomColorFolder", uri.toString()).apply()
            customFolderUri = uri
            isSettingsUnlocked = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp, bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // --- Header with Parent Lock ---
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Tori's Coloring Book", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2E7D32))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isSettingsUnlocked) "🔓" else "🔒",
                    fontSize = 24.sp,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { isSettingsUnlocked = !isSettingsUnlocked },
                                onTap = {
                                    if (!isSettingsUnlocked) {
                                        Toast.makeText(context, "Press and hold to unlock album settings", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                )

                if (isSettingsUnlocked) {
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E24AA)),
                        contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(30.dp)
                    ) { Text("📁 Album", fontSize = 12.sp) }

                    if (customFolderUri != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("❌", fontSize = 16.sp, modifier = Modifier.clickable {
                            prefs.edit().remove("CustomColorFolder").apply()
                            customFolderUri = null
                            selectedUri = null
                            selectedResId = defaultImageLibrary[0]
                            isSettingsUnlocked = false
                        })
                    }
                }
            }
        }

        // --- Thumbnail Strip ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(16.dp))
            defaultImageLibrary.forEach { resId ->
                val isSelected = selectedResId == resId
                val thumbnail = remember(resId) {
                    val bmp = BitmapFactory.decodeResource(context.resources, resId)
                    val scaled = Bitmap.createScaledBitmap(bmp, 150, 150, true)
                    val result = Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(result)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    canvas.drawBitmap(scaled, 0f, 0f, null)
                    result.asImageBitmap()
                }
                Box(modifier = Modifier.padding(end = 8.dp).size(60.dp).border(if (isSelected) 4.dp else 1.dp, if (isSelected) Color(0xFF1E88E5) else Color.Gray, RoundedCornerShape(8.dp))
                    .clickable {
                        if (!isProcessing && selectedResId != resId) {
                            currentBitmap?.let { historyStack.add(CanvasState(it, selectedUri, selectedResId)) }
                            if (historyStack.size > 15) historyStack.removeAt(0)
                            redoStack.clear()
                            selectedUri = null
                            selectedResId = resId
                        }
                    }
                ) {
                    Image(bitmap = thumbnail, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }

            // Visual Divider
            if (customFolderUri != null && customImageUris.isNotEmpty()) {
                Box(modifier = Modifier.padding(horizontal = 8.dp).width(3.dp).height(40.dp).background(Color.Gray))
            }

            if (customFolderUri != null) {
                customImageUris.forEach { uri ->
                    val isSelected = selectedUri == uri
                    val thumb = customThumbnails[uri]
                    Box(modifier = Modifier.padding(end = 8.dp).size(60.dp).border(if (isSelected) 4.dp else 1.dp, if (isSelected) Color(0xFF1E88E5) else Color.Gray, RoundedCornerShape(8.dp))
                        .clickable {
                            if (!isProcessing && selectedUri != uri) {
                                currentBitmap?.let { historyStack.add(CanvasState(it, selectedUri, selectedResId)) }
                                if (historyStack.size > 15) historyStack.removeAt(0)
                                redoStack.clear()
                                selectedResId = null
                                selectedUri = uri
                            }
                        }
                    ) {
                        if (thumb != null) Image(bitmap = thumb, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Box(modifier = Modifier.fillMaxSize().background(Color.LightGray))
                    }
                }
            }
        }

        // --- Color Palette ---
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            palette.forEach { color ->
                Box(
                    modifier = Modifier.size(36.dp).background(color, CircleShape)
                        .border(if (selectedColor == color) 4.dp else 1.dp, if (selectedColor == color) Color.Black else Color.Gray, CircleShape)
                        .clickable { selectedColor = color }
                )
            }
        }

        // --- Action Buttons Row ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "💾 Save",
                fontSize = 14.sp,
                color = if (currentBitmap != null) Color(0xFF388E3C) else Color.Gray,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(enabled = currentBitmap != null) {
                    currentBitmap?.let { bmp ->
                        isProcessing = true
                        scope.launch(Dispatchers.IO) {
                            val success = saveBitmapToGallery(context, bmp.asAndroidBitmap())
                            withContext(Dispatchers.Main) {
                                isProcessing = false
                                if (success) Toast.makeText(context, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
                                else Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            val canResetView = scale != 1f || pan != Offset.Zero
            Text(
                "🔍 Fit",
                fontSize = 14.sp,
                color = if (canResetView) Color(0xFF1E88E5) else Color.Gray,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 12.dp).clickable(enabled = canResetView) {
                    scale = 1f
                    pan = Offset.Zero
                }
            )

            Text(
                "↩️ Undo",
                fontSize = 14.sp,
                color = if (historyStack.isNotEmpty()) Color(0xFF1E88E5) else Color.Gray,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 12.dp).clickable(enabled = historyStack.isNotEmpty()) {
                    if (historyStack.isNotEmpty() && !isProcessing) {
                        currentBitmap?.let { redoStack.add(CanvasState(it, selectedUri, selectedResId)) }

                        val previousState = historyStack.removeLast()
                        skipNextLoad = true
                        selectedUri = previousState.uri
                        selectedResId = previousState.resId
                        currentBitmap = previousState.bitmap
                    }
                }
            )

            Text(
                "↪️ Redo",
                fontSize = 14.sp,
                color = if (redoStack.isNotEmpty()) Color(0xFF1E88E5) else Color.Gray,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 12.dp).clickable(enabled = redoStack.isNotEmpty()) {
                    if (redoStack.isNotEmpty() && !isProcessing) {
                        currentBitmap?.let { historyStack.add(CanvasState(it, selectedUri, selectedResId)) }
                        if (historyStack.size > 15) historyStack.removeAt(0)

                        val nextState = redoStack.removeLast()
                        skipNextLoad = true
                        selectedUri = nextState.uri
                        selectedResId = nextState.resId
                        currentBitmap = nextState.bitmap
                    }
                }
            )

            Text(
                "🔄 Reset",
                fontSize = 14.sp,
                color = Color.Red,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    scope.launch {
                        isProcessing = true
                        currentBitmap?.let { historyStack.add(CanvasState(it, selectedUri, selectedResId)) }
                        if (historyStack.size > 15) historyStack.removeAt(0)
                        redoStack.clear()

                        scale = 1f
                        pan = Offset.Zero

                        withContext(Dispatchers.IO) {
                            if (selectedUri != null) currentBitmap = loadMutableBitmapFromUri(context, selectedUri!!)
                            else if (selectedResId != null) currentBitmap = loadMutableBitmapFromRes(context, selectedResId!!)
                        }
                        isProcessing = false
                    }
                }
            )
        }

        // --- Interactive Canvas ---
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)
            .clipToBounds()
            .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
            .background(Color.White)
        ) {
            if (currentBitmap != null) {
                Canvas(
                    modifier = Modifier.fillMaxSize()
                        .transformable(state = transformableState)
                        .pointerInput(currentBitmap) {
                            detectTapGestures { offset ->
                                if (isProcessing || currentBitmap == null) return@detectTapGestures

                                val centerX = size.width / 2f
                                val centerY = size.height / 2f

                                val unpannedX = offset.x - pan.x
                                val unpannedY = offset.y - pan.y

                                val unscaledX = (unpannedX - centerX) / scale + centerX
                                val unscaledY = (unpannedY - centerY) / scale + centerY

                                val bmpWidth = currentBitmap!!.width.toFloat()
                                val bmpHeight = currentBitmap!!.height.toFloat()
                                val imgScale = minOf(size.width / bmpWidth, size.height / bmpHeight)

                                val scaledWidth = bmpWidth * imgScale
                                val scaledHeight = bmpHeight * imgScale

                                val imgOffsetX = (size.width - scaledWidth) / 2f
                                val imgOffsetY = (size.height - scaledHeight) / 2f

                                val tapX = ((unscaledX - imgOffsetX) / imgScale).toInt()
                                val tapY = ((unscaledY - imgOffsetY) / imgScale).toInt()

                                if (tapX in 0 until currentBitmap!!.width && tapY in 0 until currentBitmap!!.height) {
                                    val androidBmp = currentBitmap!!.asAndroidBitmap()
                                    val targetColor = androidBmp.getPixel(tapX, tapY)

                                    if (colorMatch(targetColor, android.graphics.Color.BLACK, 80)) return@detectTapGestures

                                    isProcessing = true
                                    val colorToFill = selectedColor.toArgb()

                                    scope.launch(Dispatchers.IO) {
                                        withContext(Dispatchers.Main) {
                                            historyStack.add(CanvasState(currentBitmap!!, selectedUri, selectedResId))
                                            if (historyStack.size > 15) historyStack.removeAt(0)
                                            redoStack.clear()
                                        }

                                        val newAndroidBmp = floodFill(androidBmp, tapX, tapY, colorToFill)

                                        withContext(Dispatchers.Main) {
                                            currentBitmap = newAndroidBmp.asImageBitmap()
                                            isProcessing = false
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    withTransform({
                        translate(left = pan.x, top = pan.y)
                        scale(scaleX = scale, scaleY = scale, pivot = Offset(size.width / 2f, size.height / 2f))
                    }) {
                        val bmpWidth = currentBitmap!!.width.toFloat()
                        val bmpHeight = currentBitmap!!.height.toFloat()
                        val imgScale = minOf(size.width / bmpWidth, size.height / bmpHeight)

                        val scaledWidth = bmpWidth * imgScale
                        val scaledHeight = bmpHeight * imgScale

                        val offsetX = (size.width - scaledWidth) / 2f
                        val offsetY = (size.height - scaledHeight) / 2f

                        drawImage(
                            image = currentBitmap!!,
                            dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
                            dstSize = IntSize(scaledWidth.toInt(), scaledHeight.toInt())
                        )
                    }
                }

                if (isProcessing) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF1E88E5))
                    }
                }
            } else if (isProcessing) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}