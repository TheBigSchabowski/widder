package widder.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo

@Composable
fun App(vm: AppViewModel, modifier: Modifier = Modifier) {
    WidderTheme {
        // Learning time only counts while the window has the focus.
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(focused) { if (focused) vm.onResume() else vm.foreground = false }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier.fillMaxSize()) {
                when (val s = vm.screen) {
                    Screen.Home -> HomeScreen(vm)
                    is Screen.Decks -> DecksScreen(vm, s.kind)
                    is Screen.Browse -> BrowseScreen(vm, s.deckId)
                    Screen.EditCard -> EditorScreen(vm)
                    is Screen.Review -> ReviewScreen(vm)
                    is Screen.Train -> TrainScreen(vm)
                    Screen.Settings -> SettingsScreen(vm)
                }
                AudioEditorScreen(vm)
                RecordingOverlay(vm)
                Dialogs(vm)
                Notices(vm)
            }
        }
    }
}
