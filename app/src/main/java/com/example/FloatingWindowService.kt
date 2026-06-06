package com.example

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import java.io.File

enum class WindowType { WEB, FILE }

class WindowState(
    val id: String,
    val type: WindowType,
    initialUrl: String = "https://www.google.com",
    initialPath: String = ""
) {
    var x by mutableStateOf(100)
    var y by mutableStateOf(200)
    var width by mutableStateOf(350)  // in DP
    var height by mutableStateOf(480) // in DP
    var opacity by mutableStateOf(1.0f)
    var isMinimized by mutableStateOf(false)
    var isMaximized by mutableStateOf(false)
    var isDocked by mutableStateOf(false)
    var dockEdge by mutableStateOf("none") // "left", "right"
    var isClickThrough by mutableStateOf(false)
    
    // Web Browser State
    var currentUrl by mutableStateOf(initialUrl)
    var webTitle by mutableStateOf("Web Browser")
    var isLoadingWeb by mutableStateOf(false)
    var webProgress by mutableStateOf(0)
    var webViewInstance: WebView? = null
    
    // File Browser State
    var currentPath by mutableStateOf(initialPath)
    var searchFilter by mutableStateOf("")
    var editingFilePath by mutableStateOf<String?>(null)
    var editingFileContent by mutableStateOf("")
}

class FloatingWindowService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private val viewsMap = mutableMapOf<String, WindowViewWrapper>()

    companion object {
        const val CHANNEL_ID = "qslide_channel"
        const val NOTIFICATION_ID = 4848
        
        const val ACTION_CREATE_WINDOW = "com.example.ACTION_CREATE_WINDOW"
        const val ACTION_RESTORE_TOUCH = "com.example.ACTION_RESTORE_TOUCH"
        const val ACTION_CLOSE_ALL = "com.example.ACTION_CLOSE_ALL"
        const val ACTION_CLOSE_WINDOW = "com.example.ACTION_CLOSE_WINDOW"
        
        const val EXTRA_WINDOW_TYPE = "EXTRA_WINDOW_TYPE"
        const val EXTRA_INITIAL_URL = "EXTRA_INITIAL_URL"
        const val EXTRA_INITIAL_PATH = "EXTRA_INITIAL_PATH"
        const val EXTRA_WINDOW_ID = "EXTRA_WINDOW_ID"

        // Globally accessible Compose state list for visual synchronicity
        val activeWindowsState = mutableStateListOf<WindowState>()
    }

    class WindowViewWrapper(
        val state: WindowState,
        val view: View,
        val params: WindowManager.LayoutParams
    )

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        })
        
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_CREATE_WINDOW -> {
                val typeStr = intent.getStringExtra(EXTRA_WINDOW_TYPE) ?: WindowType.WEB.name
                val type = WindowType.valueOf(typeStr)
                val url = intent.getStringExtra(EXTRA_INITIAL_URL) ?: "https://www.google.com"
                
                var defaultPath = intent.getStringExtra(EXTRA_INITIAL_PATH) ?: ""
                if (defaultPath.isEmpty()) {
                    val rootDir = File(filesDir, "QSlide_Files")
                    if (!rootDir.exists()) rootDir.mkdirs()
                    seedStarterFiles(rootDir)
                    defaultPath = rootDir.absolutePath
                }

                createNewFloatingWindow(type, url, defaultPath)
            }
            ACTION_RESTORE_TOUCH -> {
                // Restore touch input capability for all click-through windows
                activeWindowsState.forEach { state ->
                    if (state.isClickThrough) {
                        state.isClickThrough = false
                        updateWindowLayout(state)
                    }
                }
                Toast.makeText(this, "Touchable overlays restored", Toast.LENGTH_SHORT).show()
            }
            ACTION_CLOSE_WINDOW -> {
                val id = intent.getStringExtra(EXTRA_WINDOW_ID)
                if (id != null) {
                    removeFloatingWindow(id)
                }
            }
            ACTION_CLOSE_ALL -> {
                closeAllAndStop()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNewFloatingWindow(type: WindowType, initialUrl: String, initialPath: String) {
        val id = "qslide_${System.currentTimeMillis()}"
        
        // Offset cascading coordinates for nested windows
        val offsetMultiplier = activeWindowsState.size % 4
        val spawnX = 60 + (offsetMultiplier * 40)
        val spawnY = 120 + (offsetMultiplier * 50)

        val state = WindowState(id, type, initialUrl, initialPath).apply {
            x = spawnX
            y = spawnY
        }
        
        // Initialize custom layouts
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowService)
            setViewTreeViewModelStoreOwner(this@FloatingWindowService)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowService)
        }

        val widthPx = dpToPx(state.width, this)
        val heightPx = dpToPx(state.height, this)

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = state.x
            y = state.y
        }

        composeView.setContent {
            QSlideWindowContainer(
                state = state,
                onClose = { removeFloatingWindow(id) },
                onDrag = { dx, dy ->
                    state.x += dx.toInt()
                    state.y += dy.toInt()
                    updateWindowLayout(state)
                },
                onDragEnd = {
                    handleDragRelease(state)
                },
                onResize = { dw, dh ->
                    state.width = (state.width + dw.toInt()).coerceIn(200, 650)
                    state.height = (state.height + dh.toInt()).coerceIn(220, 850)
                    updateWindowLayout(state)
                },
                onFocusRequest = { requestFocus ->
                    toggleWindowFocus(state, requestFocus)
                },
                onStateUpdate = {
                    updateWindowLayout(state)
                }
            )
        }

        val wrapper = WindowViewWrapper(state, composeView, params)
        viewsMap[id] = wrapper
        activeWindowsState.add(state)

        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to display floating window: ${e.message}", Toast.LENGTH_LONG).show()
            activeWindowsState.remove(state)
            viewsMap.remove(id)
            if (activeWindowsState.isEmpty()) {
                stopSelf()
            }
        }
    }

    private fun handleDragRelease(state: WindowState) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val windowWidthPx = dpToPx(state.width, this)

        val leftThreshold = screenWidth * 0.08f
        val rightThreshold = screenWidth * 0.92f - windowWidthPx

        if (state.x < leftThreshold) {
            state.isDocked = true
            state.dockEdge = "left"
        } else if (state.x > rightThreshold) {
            state.isDocked = true
            state.dockEdge = "right"
        } else {
            state.isDocked = false
            state.dockEdge = "none"
        }
        updateWindowLayout(state)
    }

    private fun toggleWindowFocus(state: WindowState, focusable: Boolean) {
        val wrapper = viewsMap[state.id] ?: return
        val params = wrapper.params
        
        if (focusable) {
            // Remove NOT_FOCUSABLE to allow typing / soft input
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            // Re-apply to let background apps handle focus clicks
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(wrapper.view, params)
    }

    private fun updateWindowLayout(state: WindowState) {
        val wrapper = viewsMap[state.id] ?: return
        val params = wrapper.params

        // Support docked visual tab size
        if (state.isDocked && !state.isMinimized) {
            params.width = dpToPx(70, this)
            params.height = dpToPx(130, this)
            val displayMetrics = resources.displayMetrics
            if (state.dockEdge == "left") {
                params.x = 0
            } else {
                params.x = displayMetrics.widthPixels - params.width
            }
        } else if (state.isMinimized) {
            // Collapsed circular bubble state represents minimized mode
            params.width = dpToPx(72, this)
            params.height = dpToPx(72, this)
            params.x = state.x
            params.y = state.y
        } else {
            // Standard restore layout parameters
            params.width = dpToPx(state.width, this)
            params.height = dpToPx(state.height, this)
            params.x = state.x
            params.y = state.y
        }

        // Tapping opacity click-through flags
        if (state.isClickThrough) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }

        try {
            windowManager.updateViewLayout(wrapper.view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeFloatingWindow(winId: String, stopServiceIfEmpty: Boolean = true) {
        val wrapper = viewsMap.remove(winId)
        if (wrapper != null) {
            try {
                windowManager.removeView(wrapper.view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            activeWindowsState.removeAll { it.id == winId }
            
            // Safe cleanup of web view resources
            try {
                wrapper.state.webViewInstance?.let { webView ->
                    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (stopServiceIfEmpty && activeWindowsState.isEmpty()) {
            try {
                stopSelf()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun closeAllAndStop() {
        viewsMap.keys.toList().forEach { id ->
            removeFloatingWindow(id, stopServiceIfEmpty = false)
        }
        try {
            stopSelf()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun seedStarterFiles(dir: File) {
        try {
            val welcomeFile = File(dir, "welcome_qslide.txt")
            if (!welcomeFile.exists()) {
                welcomeFile.writeText(
                    "Welcome to the QSlide Floating File Browser!\n\n" +
                    "This environment enables true multi-window multitasking on Android. You can drag, pinch-to-resize, and control translucency just like on LG's classic smartphones.\n\n" +
                    "Feel free to create new text files, make directories, search items, or tap an HTML file to instantly load a rendering preview in a paired QSlide Web Browser!"
                )
            }
            
            val dashboardHtml = File(dir, "dashboard_view.html")
            if (!dashboardHtml.exists()) {
                dashboardHtml.writeText(
                    "<!DOCTYPE html>\n<html>\n" +
                    "<head>\n" +
                    "<meta name='viewport' content='width=device-width, initial-scale=1.0'>\n" +
                    "<style>\n" +
                    "  body { font-family: sans-serif; background-color: #121212; color: #E0E0E0; padding: 20px; text-align: center; }\n" +
                    "  h1 { color: #D0BCFF; font-size: 20px; margin-bottom: 5px; }\n" +
                    "  p { font-size: 13px; color: #B0B0B0; line-height: 1.4; }\n" +
                    "  .badge { display: inline-block; padding: 5px 12px; background: #381E72; color: #E8DDFF; border-radius: 12px; font-weight: bold; font-size: 11px; margin-top: 10px; }\n" +
                    "</style>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "  <h1>Q-Slide Framework Rendering</h1>\n" +
                    "  <p>Local HTML loaded perfectly inside your companion floating WebView. Multi-window integration makes visual communication seamless.</p>\n" +
                    "  <div class='badge'>HTML Rendering OK</div>\n" +
                    "</body>\n" +
                    "</html>"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Q-Slide Floating Overlays",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background persistence for Q-Slide floating browsers."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val actionIntent1 = Intent(this, FloatingWindowService::class.java).apply { action = ACTION_RESTORE_TOUCH }
        val unlockIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 1, actionIntent1, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 1, actionIntent1, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        
        val actionIntent2 = Intent(this, FloatingWindowService::class.java).apply { action = ACTION_CLOSE_ALL }
        val closeAllIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 2, actionIntent2, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 2, actionIntent2, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val mainActivityIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Q-Slide Overlays Active")
            .setContentText("Translucent windows are running. Adjust opacity slider on tabs.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(mainActivityIntent)
            .addAction(android.R.drawable.ic_partial_secure, "Unfaze / Enable Touch", unlockIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close All Windows", closeAllIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun dpToPx(dp: Int, context: Context): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        viewsMap.keys.toList().forEach { id ->
            removeFloatingWindow(id, stopServiceIfEmpty = false)
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun QSlideWindowContainer(
    state: WindowState,
    onClose: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onResize: (dw: Float, dh: Float) -> Unit,
    onFocusRequest: (Boolean) -> Unit,
    onStateUpdate: () -> Unit
) {
    val context = LocalContext.current
    var isPinching by remember { mutableStateOf(false) }

    // Floating bubble UI if minimized
    if (state.isMinimized) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .shadow(12.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    if (state.type == WindowType.WEB) MaterialTheme.colorScheme.primaryContainer 
                    else MaterialTheme.colorScheme.secondaryContainer
                )
                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable {
                    state.isMinimized = false
                    onStateUpdate()
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        },
                        onDragEnd = onDragEnd
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(
                    imageVector = if (state.type == WindowType.WEB) Icons.Default.Public else Icons.Default.Folder,
                    contentDescription = "Restore",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
                Text("Restore", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        return
    }

    // Floating edge tab UI if docked to sides
    if (state.isDocked) {
        val isLeft = state.dockEdge == "left"
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(10.dp, RoundedCornerShape(if (isLeft) 0.dp else 12.dp, if (isLeft) 12.dp else 0.dp, if (isLeft) 12.dp else 0.dp, if (isLeft) 0.dp else 12.dp))
                .clip(RoundedCornerShape(if (isLeft) 0.dp else 12.dp, if (isLeft) 12.dp else 0.dp, if (isLeft) 12.dp else 0.dp, if (isLeft) 0.dp else 12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    2.dp, 
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    RoundedCornerShape(if (isLeft) 0.dp else 12.dp, if (isLeft) 12.dp else 0.dp, if (isLeft) 12.dp else 0.dp, if (isLeft) 0.dp else 12.dp)
                )
                .clickable {
                    state.isDocked = false
                    // push outwards a bit so user can grab it
                    state.x = if (isLeft) 50 else (context.resources.displayMetrics.widthPixels - (state.width * context.resources.displayMetrics.density).toInt() - 50)
                    onStateUpdate()
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        },
                        onDragEnd = onDragEnd
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(4.dp)
            ) {
                Icon(
                    imageVector = if (isLeft) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                    contentDescription = "Pull Dock Window",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Icon(
                    imageVector = if (state.type == WindowType.WEB) Icons.Default.Public else Icons.Default.Folder,
                    contentDescription = "Type",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (state.type == WindowType.WEB) "WEB" else "FILE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    // Standard high-fidelity Q-Slide floating window
    Card(
        modifier = Modifier
            .fillMaxSize()
            .alpha(state.opacity)
            .shadow(16.dp, RoundedCornerShape(14.dp))
            .border(
                if (state.isClickThrough) 2.dp else 1.dp,
                if (state.isClickThrough) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) 
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                RoundedCornerShape(14.dp)
            )
            // Gesture: Pinch-To-Resize anywhere on the window bounding border
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) {
                        isPinching = true
                        val scaleFactor = if (zoom > 1f) 1.05f else 0.95f
                        val dw = ((state.width * scaleFactor) - state.width)
                        val dh = ((state.height * scaleFactor) - state.height)
                        onResize(dw, dh)
                    }
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // TITLE BAR (Always touchable, handles dragging and standard header actions)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        if (state.isClickThrough) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    )
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.x, dragAmount.y)
                            },
                            onDragEnd = {
                                isPinching = false
                                onDragEnd()
                            }
                        )
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (state.type == WindowType.WEB) Icons.Default.Public else Icons.Default.Folder,
                    contentDescription = "",
                    tint = if (state.isClickThrough) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
                
                Spacer(modifier = Modifier.width(6.dp))
                
                Text(
                    text = if (state.type == WindowType.WEB) state.webTitle else "Files: /" + File(state.currentPath).name,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (state.isClickThrough) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Minimize Header Button
                IconButton(
                    onClick = {
                        state.isMinimized = true
                        onStateUpdate()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Minimize,
                        contentDescription = "Minimize App",
                        tint = if (state.isClickThrough) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // LG QSlide Feature: Input Lock / Pass-Through toggling
                IconButton(
                    onClick = {
                        state.isClickThrough = !state.isClickThrough
                        onStateUpdate()
                        if (state.isClickThrough) {
                            Toast.makeText(context, "Click-through on. Contents unclickable! Use notification to unlock.", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (state.isClickThrough) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "Pass-Through",
                        tint = if (state.isClickThrough) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Close Header button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close overlay",
                        tint = if (state.isClickThrough) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // CONTROLS PANEL (Transparency Slider & Dynamic Navigation Utilities)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 4.dp, horizontal = 8.dp)
            ) {
                // QSlide Classic Style Slider Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Opacity Control",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Translucency: ${(state.opacity * 100).toInt()}%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Opacity Slider widget
                    Slider(
                        value = state.opacity,
                        onValueChange = {
                            state.opacity = it.coerceIn(0.12f, 1.0f)
                        },
                        valueRange = 0.12f..1.0f,
                        modifier = Modifier
                            .width(130.dp)
                            .height(18.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)

                // Layout custom features dependent on QSlide State Type
                if (state.type == WindowType.WEB) {
                    WebNavigationControlHeader(state = state, onFocusRequest = onFocusRequest)
                } else {
                    FileExplorerNavigationHeader(state = state, onFocusRequest = onFocusRequest)
                }
            }

            // WINDOW VIEWER FRAME CONTENT
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (state.type == WindowType.WEB) {
                    QSlideWebFrame(state = state)
                } else {
                    QSlideFileFrame(state = state, onFocusRequest = onFocusRequest)
                }
                
                // Overlay Pinch Guide Overlay
                if (isPinching) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                "Pinch Resizing Window...",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }

            // WINDOW FOOTER (Handles diagonal drag resize)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                // Resize Handle Drag Grip in bottom right
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val scale = context.resources.displayMetrics.density
                                val dw = (dragAmount.x / scale)
                                val dh = (dragAmount.y / scale)
                                onResize(dw, dh)
                            }
                        },
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Canvas(
                        modifier = Modifier
                            .size(14.dp)
                            .padding(bottom = 3.dp, end = 3.dp)
                    ) {
                        val pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                        drawLine(
                            color = Color.Gray,
                            start = Offset(size.width, size.height - 8f),
                            end = Offset(size.width - 8f, size.height),
                            strokeWidth = 2f
                        )
                        drawLine(
                            color = Color.Gray,
                            start = Offset(size.width, size.height - 4f),
                            end = Offset(size.width - 4f, size.height),
                            strokeWidth = 2f
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WebNavigationControlHeader(
    state: WindowState,
    onFocusRequest: (Boolean) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var urlInput by remember { mutableStateOf(state.currentUrl) }

    // Sync input field when state redirects autonomously
    LaunchedEffect(state.currentUrl) {
        urlInput = state.currentUrl
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                state.webViewInstance?.goBack()
            },
            modifier = Modifier.size(28.dp),
            enabled = state.webViewInstance?.canGoBack() ?: false
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Web Back",
                modifier = Modifier.size(18.dp)
            )
        }

        IconButton(
            onClick = {
                state.webViewInstance?.goForward()
            },
            modifier = Modifier.size(28.dp),
            enabled = state.webViewInstance?.canGoForward() ?: false
        ) {
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "Web Forward",
                modifier = Modifier.size(18.dp)
            )
        }

        IconButton(
            onClick = {
                state.webViewInstance?.reload()
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Web Reload",
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Address Field
        Box(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                textStyle = TextStyle(
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        focusManager.clearFocus()
                        onFocusRequest(false)
                        var finalUrl = urlInput.trim()
                        if (finalUrl.isNotEmpty()) {
                            if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://") && !finalUrl.startsWith("file://")) {
                                finalUrl = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(finalUrl, "UTF-8")
                            }
                            state.currentUrl = finalUrl
                        }
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            onFocusRequest(true) // Dynamic LayoutParams Keyboard Enable!
                        }
                    }
            )
            if (urlInput.isEmpty()) {
                Text(
                    "Search or enter address...",
                    fontSize = 11.sp,
                    color = Color.LightGray
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        IconButton(
            onClick = {
                state.currentUrl = "https://www.google.com"
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = "Web Home",
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun QSlideWebFrame(state: WindowState) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            state.isLoadingWeb = true
                            url?.let { state.currentUrl = it }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            state.isLoadingWeb = false
                            state.webTitle = view?.title ?: "Web Browser"
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            state.webProgress = newProgress
                        }
                    }
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        allowFileAccess = true
                        allowContentAccess = true
                    }
                    state.webViewInstance = this
                    loadUrl(state.currentUrl)
                }
            },
            update = { webView ->
                if (webView.url != state.currentUrl) {
                    webView.loadUrl(state.currentUrl)
                }
            }
        )

        if (state.isLoadingWeb) {
            LinearProgressIndicator(
                progress = { state.webProgress.toFloat() / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.TopStart),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
fun FileExplorerNavigationHeader(
    state: WindowState,
    onFocusRequest: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var inputSearch by remember { mutableStateOf(state.searchFilter) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var newFileNameInput by remember { mutableStateOf("") }
    var isCreatingFolder by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                val currentFolder = File(state.currentPath)
                val parentFile = currentFolder.parentFile
                if (parentFile != null && parentFile.absolutePath.startsWith(context.filesDir.absolutePath)) {
                    state.currentPath = parentFile.absolutePath
                } else {
                    Toast.makeText(context, "Hierarchy boundary reached!", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.size(28.dp),
            enabled = state.currentPath != context.filesDir.absolutePath
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "File Parent",
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Search in directory field
        Box(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = inputSearch,
                onValueChange = {
                    inputSearch = it
                    state.searchFilter = it
                },
                textStyle = TextStyle(
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        onFocusRequest(false)
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            onFocusRequest(true)
                        }
                    }
            )
            if (inputSearch.isEmpty()) {
                Text(
                    "Filter directories...",
                    fontSize = 11.sp,
                    color = Color.LightGray
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Quick folder make button
        IconButton(
            onClick = {
                isCreatingFolder = true
                newFileNameInput = ""
                showCreateFileDialog = true
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CreateNewFolder,
                contentDescription = "Mkdir Folder",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }

        // Quick file text make button
        IconButton(
            onClick = {
                isCreatingFolder = false
                newFileNameInput = ""
                showCreateFileDialog = true
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Create txt File",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    // Modal creation inline dialogue
    if (showCreateFileDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreateFileDialog = false
                onFocusRequest(false)
            },
            title = {
                Text(
                    text = if (isCreatingFolder) "Make New Folder" else "Create Text File (.txt)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = newFileNameInput,
                        onValueChange = { newFileNameInput = it },
                        placeholder = { Text("Enter plain name...") },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged {
                                if (it.isFocused) onFocusRequest(true)
                            }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCreateFileDialog = false
                        onFocusRequest(false)
                        val nameStr = newFileNameInput.trim()
                        if (nameStr.isNotEmpty()) {
                            val targetFile = File(state.currentPath, nameStr)
                            try {
                                if (isCreatingFolder) {
                                    targetFile.mkdirs()
                                } else {
                                    val finalFile = if (nameStr.endsWith(".txt") || nameStr.endsWith(".html")) targetFile else File(state.currentPath, "$nameStr.txt")
                                    finalFile.createNewFile()
                                    finalFile.writeText("Write content inside this document overlay editor! Built automatically.")
                                }
                                Toast.makeText(context, "${if (isCreatingFolder) "Folder" else "File"} created!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text("Confirm", fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreateFileDialog = false
                        onFocusRequest(false)
                    }
                ) {
                    Text("Cancel", fontSize = 12.sp)
                }
            }
        )
    }
}

@Composable
fun QSlideFileFrame(
    state: WindowState,
    onFocusRequest: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val currentFolder = File(state.currentPath)
    var filesList by remember { mutableStateOf<List<File>>(emptyList()) }

    // Reactively refresh directories on updates
    LaunchedEffect(state.currentPath, state.searchFilter, state.editingFilePath) {
        val root = File(state.currentPath)
        if (!root.exists()) {
            root.mkdirs()
        }
        val all = root.listFiles()?.toList() ?: emptyList()
        filesList = if (state.searchFilter.isEmpty()) {
            all.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } else {
            all.filter { it.name.contains(state.searchFilter, ignoreCase = true) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }

    // Embed visual editor pane if a file is clicked for write/read
    if (state.editingFilePath != null) {
        val editingFile = File(state.editingFilePath!!)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Editing: ${editingFile.name}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                
                IconButton(
                    onClick = {
                        try {
                            editingFile.writeText(state.editingFileContent)
                            state.editingFilePath = null
                            onFocusRequest(false)
                            Toast.makeText(context, "Document Saved!", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Save Failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = "Save Draft",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = {
                        state.editingFilePath = null
                        onFocusRequest(false)
                    },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close pane",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            OutlinedTextField(
                value = state.editingFileContent,
                onValueChange = { state.editingFileContent = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onFocusChanged {
                        if (it.isFocused) onFocusRequest(true)
                    },
                textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                placeholder = { Text("Begin composition...", fontSize = 11.sp) }
            )
        }
        return
    }

    if (filesList.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "",
                    tint = Color.LightGray,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text("Directory is Empty", fontSize = 11.sp, color = Color.Gray)
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(filesList) { file ->
            FileRowItem(
                file = file,
                onFolderClick = {
                    state.currentPath = file.absolutePath
                },
                onFileClick = {
                    val ext = file.name.substringAfterLast('.', "").lowercase()
                    if (ext == "txt" || ext == "html" || ext == "cfg" || ext == "json") {
                        try {
                            state.editingFileContent = file.readText()
                            state.editingFilePath = file.absolutePath
                        } catch (e: Exception) {
                            Toast.makeText(context, "Read Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "File is not standard editable text!", Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenInBrowser = {
                    // Integration feature: Spawn a paired Q-Slide Web browser rendering this file!
                    val intent = Intent(context, FloatingWindowService::class.java).apply {
                        action = FloatingWindowService.ACTION_CREATE_WINDOW
                        putExtra(FloatingWindowService.EXTRA_WINDOW_TYPE, WindowType.WEB.name)
                        putExtra(FloatingWindowService.EXTRA_INITIAL_URL, "file://" + file.absolutePath)
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                        Toast.makeText(context, "Rendering loaded in QSlide WebView", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(context, "Failed to launch companion window: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                },
                onDelete = {
                    try {
                        file.deleteRecursively()
                        // force list refresh
                        val path = state.currentPath
                        state.currentPath = ""
                        state.currentPath = path
                        Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Deletion Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}

@Composable
fun FileRowItem(
    file: File,
    onFolderClick: () -> Unit,
    onFileClick: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (file.isDirectory) onFolderClick() else onFileClick()
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val extension = file.name.substringAfterLast('.', "").lowercase()
            val icon = when {
                file.isDirectory -> Icons.Default.Folder
                extension == "html" -> Icons.Default.Html
                extension == "txt" -> Icons.Default.Description
                else -> Icons.Default.Article
            }
            val tint = when {
                file.isDirectory -> Color(0xFFFFC107)
                extension == "html" -> Color(0xFF2196F3)
                extension == "txt" -> Color(0xFF4CAF50)
                else -> Color(0xFF9E9E9E)
            }

            Icon(
                imageVector = icon,
                contentDescription = "",
                tint = tint,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = if (file.isDirectory) "Directory" else "${file.length() / 8} bytes | editor-ready",
                    fontSize = 9.sp,
                    color = Color.Gray
                )
            }

            // Quick rendering launcher if it's html
            if (extension == "html") {
                IconButton(
                    onClick = onOpenInBrowser,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Launch,
                        contentDescription = "Render File",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete File",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
