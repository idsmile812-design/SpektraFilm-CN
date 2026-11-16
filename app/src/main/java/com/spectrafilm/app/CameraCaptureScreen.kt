/*
 * Spektrafilm for Android — local still-camera capture. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CameraCaptureScreen(
    onUsePhoto: (Uri) -> Unit,
    onOpenPhotoPicker: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val store = remember(context) { CaptureFileStore(context) }
    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var captureGeneration by remember { mutableIntStateOf(0) }
    var captureError by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingFile = pendingPath?.let(::File)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        permissionDenied = !granted
    }

    fun discardPending() {
        val file = pendingFile
        if (file != null) runCatching { store.discard(file) }
        pendingPath = null
    }

    fun close() {
        captureGeneration++
        discardPending()
        capturing = false
        onClose()
    }

    BackHandler {
        if (pendingFile != null) {
            discardPending()
        } else {
            close()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            captureGeneration++
            if (activity?.isChangingConfigurations != true) {
                pendingFile?.let { runCatching { store.discard(it) } }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val cameraTitle = stringResource(R.string.camera_title)
    val captureUnavailable = stringResource(R.string.camera_capture_unavailable)
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics { paneTitle = cameraTitle },
        color = Color.Black,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::close) {
                    Icon(
                        SpectraIcons.Back,
                        contentDescription = stringResource(R.string.editor_nav_back),
                        tint = Color.White,
                    )
                }
                Text(
                    stringResource(R.string.camera_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }

            when {
                pendingFile != null -> CaptureConfirmation(
                    file = pendingFile,
                    store = store,
                    errorMessage = captureError,
                    onRetake = {
                        discardPending()
                        captureError = null
                    },
                    onUse = {
                        val file = pendingFile
                        if (file != null) {
                            val uri = runCatching { store.uriFor(file) }.getOrNull()
                            if (uri == null) {
                                captureError = captureUnavailable
                            } else {
                                pendingPath = null
                                onUsePhoto(uri)
                            }
                        }
                    },
                )
                !hasCamera -> CameraMessage(
                    body = stringResource(R.string.camera_unavailable),
                    onOpenPhotoPicker = onOpenPhotoPicker,
                )
                !permissionGranted -> PermissionExplanation(
                    denied = permissionDenied,
                    onAllow = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        runCatching {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            )
                            context.startActivity(intent)
                        }
                    },
                    onOpenPhotoPicker = onOpenPhotoPicker,
                )
                else -> CameraViewfinder(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    store = store,
                    capturing = capturing,
                    captureError = captureError,
                    onCaptureError = { captureError = it },
                    onCaptureState = { capturing = it },
                    generation = { captureGeneration },
                    onCaptured = { file, generation ->
                        if (generation == captureGeneration) {
                            pendingPath = file.absolutePath
                            capturing = false
                        } else {
                            runCatching { store.discard(file) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionExplanation(
    denied: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPhotoPicker: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            SpectraIcons.Camera,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(42.dp),
        )
        Text(
            stringResource(R.string.camera_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.camera_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.78f),
        )
        if (denied) {
            Text(
                stringResource(R.string.camera_permission_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onAllow,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.camera_action_allow)) }
        if (denied) {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.camera_action_settings)) }
        }
        TextButton(
            onClick = onOpenPhotoPicker,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.editor_source_pick_photo)) }
    }
}

@Composable
private fun CameraMessage(body: String, onOpenPhotoPicker: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(body, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        TextButton(
            onClick = onOpenPhotoPicker,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.editor_source_pick_photo)) }
    }
}

@Composable
private fun CameraViewfinder(
    context: android.content.Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    store: CaptureFileStore,
    capturing: Boolean,
    captureError: String?,
    onCaptureError: (String?) -> Unit,
    onCaptureState: (Boolean) -> Unit,
    generation: () -> Int,
    onCaptured: (File, Int) -> Unit,
) {
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var lensFacing by rememberSaveable { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var canFlip by remember { mutableStateOf(false) }

    DisposableEffect(lensFacing, lifecycleOwner) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        ready = false
        failed = false
        imageCapture = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (disposed) return@addListener
                try {
                    provider = future.get()
                    val view = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                        .build()
                    val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                    provider?.unbindAll()
                    provider?.bindToLifecycle(lifecycleOwner, selector, view, capture)
                    imageCapture = capture
                    canFlip = runCatching {
                        provider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) == true
                    }.getOrDefault(false)
                    ready = true
                } catch (_: Exception) {
                    failed = true
                    ready = false
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            imageCapture = null
            ready = false
            future.cancel(true)
            provider?.unbindAll()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            update = { view ->
                imageCapture?.targetRotation = view.display?.rotation ?: Surface.ROTATION_0
            },
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = context.getString(R.string.camera_preview_desc)
                },
        )
        when {
            failed -> Text(
                stringResource(R.string.camera_failed),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
            !ready -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (captureError != null) {
            Text(
                captureError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 132.dp, start = 24.dp, end = 24.dp),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
                enabled = canFlip && !capturing,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    SpectraIcons.FlipCamera,
                    contentDescription = stringResource(R.string.camera_action_flip),
                    tint = Color.White,
                )
            }
            Button(
                onClick = {
                    val capture = imageCapture
                    if (!ready || capture == null || capturing) return@Button
                    val file = runCatching { store.newFile() }.getOrNull()
                    if (file == null) {
                        onCaptureError(context.getString(R.string.camera_capture_unavailable))
                        return@Button
                    }
                    val requestGeneration = generation()
                    onCaptureState(true)
                    onCaptureError(null)
                    capture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(file).build(),
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                onCaptured(file, requestGeneration)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                runCatching { store.discard(file) }
                                onCaptureState(false)
                                onCaptureError(
                                    context.getString(
                                        R.string.camera_capture_failed,
                                        exception.message ?: "ImageCaptureException",
                                    ),
                                )
                            }
                        },
                    )
                },
                enabled = ready && !capturing,
                modifier = Modifier
                    .size(width = 112.dp, height = 64.dp),
                shape = RoundedCornerShape(32.dp),
            ) {
                Text(
                    if (capturing) stringResource(R.string.camera_capturing)
                    else stringResource(R.string.camera_action_capture),
                )
            }
            Box(Modifier.size(56.dp))
        }
    }
}

@Composable
private fun CaptureConfirmation(
    file: File,
    store: CaptureFileStore,
    errorMessage: String?,
    onRetake: () -> Unit,
    onUse: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                while (longest / (sample * 2) > 1600) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
                val orientation = readExifOrientation(context, store.uriFor(file))
                if (decoded == null || orientation.isIdentity) return@runCatching decoded
                val matrix = Matrix()
                if (orientation.flipH) matrix.postScale(-1f, 1f)
                matrix.postRotate(orientation.rotation.degrees.toFloat())
                Bitmap.createBitmap(
                    decoded,
                    0,
                    0,
                    decoded.width,
                    decoded.height,
                    matrix,
                    true,
                ).also { if (it !== decoded) decoded.recycle() }
            }.getOrNull()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.camera_capture_name),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator()
            }
        }
        if (errorMessage != null) {
            Text(
                errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedButton(
            onClick = onRetake,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.camera_action_retake)) }
        Button(
            onClick = onUse,
            enabled = bitmap != null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.camera_action_use)) }
    }
}
