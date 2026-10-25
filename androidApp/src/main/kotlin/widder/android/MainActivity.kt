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
import widder.core.Action
import widder.core.ActionKind
import widder.core.DirectoryStore
import widder.core.IncomingFile
import widder.core.PickKind
import widder.core.Store
import widder.ui.App
import widder.ui.AppViewModel
import widder.ui.BackgroundAudio
import widder.ui.PlatformHooks
import widder.ui.Screen

class MainActivity : ComponentActivity() {
    private val viewModel by lazy {
        // One per process: the activity is not recreated on rotation (see the manifest), and a
        // file shared from another app reaches the running instance through onNewIntent.
        holder ?: AppViewModel(Store(DirectoryStore(filesDir)), androidAudioSystem(applicationContext), Hooks).also { holder = it }
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

    private var pendingSave: Pair<ByteArray, (String?) -> Unit>? = null

    // octet-stream: the name the user keeps ("A7.wav", "A7.mid") decides the type.
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val (bytes, onDone) = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        if (uri == null) return@registerForActivityResult onDone(null)
        val ok = runCatching { contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } }.isSuccess
        onDone(if (ok) displayName(uri) else null)
    }

    private fun saveFile(suggestedName: String, bytes: ByteArray, onDone: (String?) -> Unit) {
        pendingSave = bytes to onDone
        createDocument.launch(suggestedName)
    }

    private var pendingTree: ((Uri?) -> Unit)? = null

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pendingTree?.invoke(uri)
        pendingTree = null
    }

    private fun pickTree(onResult: (Uri?) -> Unit) {
        pendingTree = onResult
        openTree.launch(null)
    }

    // A foreground notification posted before the permission stays hidden: post it again.
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) Hooks.repost() }

    /** Once per run: without the permission the background notification (and its stop button) stays hidden. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33 || askedForNotifications) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askedForNotifications = true
        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingMic?.invoke(granted)
        pendingMic = null
    }

    /**
     * The view model outlives an activity that Android destroys and recreates; its dialogs always
     * go to the activity that is showing now.
     */
    private object Hooks : PlatformHooks {
        private var folderAccess: SafFolders? = null

        override val folders: SafFolders?
            get() = current?.let { activity ->
                folderAccess ?: SafFolders(activity.applicationContext) { onResult ->
                    current?.pickTree(onResult) ?: onResult(null)
                }.also { folderAccess = it }
            }

        override fun pickFiles(kind: PickKind, onResult: (List<IncomingFile>) -> Unit) {
            current?.pickFiles(kind, onResult) ?: onResult(emptyList())
        }

        override fun requestMicrophone(onResult: (Boolean) -> Unit) {
            current?.requestMicrophone(onResult) ?: onResult(false)
        }

        override fun saveFile(suggestedName: String, bytes: ByteArray, onDone: (String?) -> Unit) {
            current?.saveFile(suggestedName, bytes, onDone) ?: onDone(null)
        }

        /** The last state asked for, so the service is only started or stopped when it changes. */
        private var background: BackgroundAudio? = null
        var appContext: android.content.Context? = null

        fun repost() {
            val context = appContext ?: return
            background?.let { PracticeService.update(context, it) }
        }

        override fun backgroundAudio(audio: BackgroundAudio?) {
            if (audio == background) return
            background = audio
            if (audio != null) current?.askForNotifications()
            val context = appContext ?: return
            PracticeService.update(context, audio)
        }

        override fun updateWidgets() {
            val context = appContext ?: return
            WidderWidgetProvider.updateAll(context)
        }
    }

    private fun pickFiles(kind: PickKind, onResult: (List<IncomingFile>) -> Unit) {
        pendingPick = onResult
        if (kind == PickKind.IMAGE) {
            openOne.launch(arrayOf("image/*"))
        } else {
            // Many file managers label .mid as octet-stream.
            openMany.launch(arrayOf("audio/*", "application/x-midi", "application/octet-stream"))
        }
    }

    private fun requestMicrophone(onResult: (Boolean) -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
        } else {
            pendingMic = onResult
            microphone.launch(Manifest.permission.RECORD_AUDIO)
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

    /** Files opened with or shared to Widder, or actions launched from a widget / shortcut. */
    private fun receive(intent: Intent?) {
        intent ?: return
        if (intent.action == WidderWidgetProvider.ACTION_WIDGET) {
            val kindName = intent.getStringExtra(WidderWidgetProvider.EXTRA_ACTION_KIND)
            val kind = kindName?.let { runCatching { ActionKind.valueOf(it) }.getOrNull() }
            if (kind != null) {
                val deckId = intent.getStringExtra(WidderWidgetProvider.EXTRA_DECK_ID)
                viewModel.runWidgetAction(Action(kind, deckId))
            }
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
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
        current = this
        Hooks.appContext = applicationContext
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
        WidderWidgetProvider.updateAll(applicationContext)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        private var holder: AppViewModel? = null
        private var current: MainActivity? = null
        private var askedForNotifications = false

        internal fun peekViewModel(): AppViewModel? = holder

        /** The notification's stop button and a swipe from the recent apps. */
        fun stopPracticeSound() {
            holder?.stopPracticeSound()
        }
    }
}
