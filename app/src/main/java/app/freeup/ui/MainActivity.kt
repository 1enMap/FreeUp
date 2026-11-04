package app.freeup.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FreeUpTheme { App(vm) }
        }
    }

    internal fun currentAccess(): Access {
        fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_VIDEO) -> Access.FULL
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
            else -> Access.NONE
        }
    }
}

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
private fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as MainActivity

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onAccess(activity.currentAccess())
    }
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) vm.startCompressing()
    }

    // Re-check access when coming back: on Android 14 you may have changed which videos are shared.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.onAccess(activity.currentAccess())
    }
    LaunchedEffect(Unit) {
        if (activity.currentAccess() == Access.NONE) permissionLauncher.launch(mediaPermissions())
    }

    BackHandler(enabled = state.screen == Screen.CONFIRM) { vm.back() }

    when {
        state.access == Access.NONE -> PermissionScreen { permissionLauncher.launch(mediaPermissions()) }
        state.screen == Screen.PROGRESS && state.progress != null -> ProgressScreen(
            progress = state.progress!!,
            onStop = vm::stop,
            onDone = vm::finishProgress,
        )
        state.screen == Screen.CONFIRM -> ConfirmScreen(
            state = state,
            onBack = vm::back,
            onQuality = vm::setQuality,
            onStart = {
                vm.writeRequest()?.let { writeLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            },
        )
        else -> VideoGridScreen(
            state = state,
            onToggle = vm::toggle,
            onSelectTop = { vm.selectTopBiggest() },
            onClear = vm::clearSelection,
            onReview = vm::openConfirm,
            onAllowMore = { permissionLauncher.launch(mediaPermissions()) },
        )
    }
}
