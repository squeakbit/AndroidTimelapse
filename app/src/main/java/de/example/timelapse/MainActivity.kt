package de.example.timelapse

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.example.timelapse.service.CameraForegroundService
import de.example.timelapse.ui.CameraTab
import de.example.timelapse.ui.HomeTab
import de.example.timelapse.ui.SettingsTab
import de.example.timelapse.ui.theme.TimelapseTheme

class MainActivity : ComponentActivity() {
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val mediaPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        cameraPermission.launch(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) {
            mediaPermission.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
        } else {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        setContent { TimelapseTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        CameraForegroundService.ensureServiceRunning(this)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppRoot() {
        var tab by remember { mutableIntStateOf(0) }
        var liveEnabled by remember { mutableStateOf(false) }
        var showGhost by remember { mutableStateOf(false) }
        var testModeEnabled by remember { mutableStateOf(false) }
        LaunchedEffect(liveEnabled) { if (liveEnabled) testModeEnabled = false }
        LaunchedEffect(testModeEnabled) { if (testModeEnabled) liveEnabled = false }
        
        // Auto-disable Live View and Ghost when leaving the Camera tab (tab 1)
        LaunchedEffect(tab) {
            if (tab != 1) {
                liveEnabled = false
                showGhost = false
            }
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) {
                    liveEnabled = false
                    testModeEnabled = false
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_start)) }, icon = { Icon(Icons.Default.PlayArrow, null) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_camera)) }, icon = { Icon(Icons.Default.CameraAlt, null) })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.tab_setup)) }, icon = { Icon(Icons.Default.Settings, null) })
                }
                when (tab) {
                    0 -> HomeTab(
                        testModeEnabled = testModeEnabled,
                        onTestModeChange = { testModeEnabled = it },
                        onEnsureCameraServiceRunning = { CameraForegroundService.ensureServiceRunning(this@MainActivity) }
                    )
                    1 -> CameraTab(
                        liveEnabled = liveEnabled, 
                        onLiveEnabledChange = { liveEnabled = it },
                        showGhost = showGhost,
                        onShowGhostChange = { showGhost = it }
                    )
                    else -> SettingsTab(onRequestIgnoreBatteryOptimizations = { requestIgnoreBatteryOptimizations() })
                }
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}
