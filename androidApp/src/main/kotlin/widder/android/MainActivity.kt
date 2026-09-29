package widder.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import widder.audio.androidAudioSystem
import widder.core.DirectoryStore
import widder.core.IncomingFile
import widder.core.PickKind
import widder.core.Store
import widder.ui.App
import widder.ui.AppViewModel
import widder.ui.PlatformHooks
import widder.ui.Screen

class MainActivity : ComponentActivity() {
    private val viewModel by lazy {
        // One per process: the activity is not recreated on rotation (see the manifest), and a
        // file shared from another app reaches the running instance through onNewIntent.
        holder ?: AppViewModel(Store(DirectoryStore(filesDir)), androidAudioSystem(cacheDir), hooks).also { holder = it }
    }

    private var pendingPick: ((List<IncomingFile>) -> Unit)? = null
    private var pendingMic: ((Boolean) -> Unit)? = null

    private val openMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val done = pendingPick ?: return@registerForActivityResult
        pendingPick = null
        done(uris.mapNotNull(::read))
    }

    private val openOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val done = pendingPick ?: return@registerForActivityResult
        pendingPick = null
        done(listOfNotNull(uri?.let(::read)))
    }

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingMic?.invoke(granted)
        pendingMic = null
    }

    private val hooks = object : PlatformHooks {
        override fun pickFiles(kind: PickKind, onResult: (List<IncomingFile>) -> Unit) {
            pendingPick = onResult
            if (kind == PickKind.IMAGE) {
                openOne.launch(arrayOf("image/*"))
            } else {
                // Many file managers label .mid as octet-stream.
                openMany.launch(arrayOf("audio/*", "application/x-midi", "application/octet-stream"))
            }
        }

        override fun requestMicrophone(onResult: (Boolean) -> Unit) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                onResult(true)
            } else {
                pendingMic = onResult
                microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun read(uri: Uri): IncomingFile? = try {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        bytes?.let { IncomingFile(displayName(uri), it) }
    } catch (e: Exception) {
        null
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Datei"

    /** Files opened with or shared to Widder. */
    private fun receive(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(stream(intent))
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        viewModel.receiveFiles(uris.mapNotNull(::read))
        // Handled once; a rotation must not import the same files again.
        setIntent(Intent(this, MainActivity::class.java))
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm = viewModel
            // Back closes the innermost thing that is open instead of leaving the app.
            BackHandler(enabled = vm.screen != Screen.Home || vm.audioEditor != null || vm.recording != null || vm.showRewardOnly || vm.pendingImport != null || vm.importPreview != null) {
                vm.back()
            }
            App(vm, Modifier.safeDrawingPadding())
        }
        if (savedInstanceState == null) receive(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }

    companion object {
        private var holder: AppViewModel? = null
    }
}
