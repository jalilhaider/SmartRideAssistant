package com.smartride.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.smartride.assistant.capture.MonitorBus
import com.smartride.assistant.capture.ScreenCaptureService
import com.smartride.assistant.ui.Actions
import com.smartride.assistant.ui.AppRoot
import com.smartride.assistant.ui.MainViewModel
import com.smartride.assistant.ui.SmartRideTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val container get() = (application as SmartRideApp).container

    /** Result of the official Android "start recording or casting" consent dialog. */
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_START)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, r.resultCode)
                .putExtra(ScreenCaptureService.EXTRA_DATA, data)
            ContextCompat.startForegroundService(this, i)
        } else {
            vm.postMessage("Screen-capture permission was not granted, so monitoring did not start.")
        }
    }

    /** Asked once when the app opens so GPS works for screenshot tests too. */
    private val startupPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        container.location.start()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        container.location.start()
        // Notifications or location may be denied: monitoring still works, with the limits shown in the app.
        launchConsent()
    }

    private fun launchConsent() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        consentLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun requestStart() {
        if (MonitorBus.state.value.running) return
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (needed.isEmpty()) launchConsent() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requestStop() {
        startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startupPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        setContent {
            SmartRideTheme {
                AppRoot(vm, Actions(start = ::requestStart, stop = ::requestStop))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.location.start()
    }

    override fun onStop() {
        // keep GPS running only while a monitoring session needs it
        if (!MonitorBus.state.value.running) container.location.stop()
        super.onStop()
    }
}
